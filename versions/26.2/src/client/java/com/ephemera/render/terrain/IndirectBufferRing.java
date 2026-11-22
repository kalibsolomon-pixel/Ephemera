package com.ephemera.render.terrain;

import com.ephemera.core.terrain.IndirectCommandList;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Four rotating indirect-parameter buffers, one per frame in flight, so a frame never
 * overwrites commands an earlier frame may still be reading. Each buffer grows lazily to the
 * next power of two; the replaced buffer is closed (both backends defer the actual release
 * until the GPU is done with it). Whenever the device supports persistent mapping (OpenGL with
 * {@code ARB_buffer_storage}, and Vulkan, whose {@code MAP_WRITE} buffers are host-visible +
 * coherent, landing in ReBAR VRAM where available) the commands are written straight into the
 * mapped buffer ({@link #mapsDirectly}, {@link #next}): no staging copy, no
 * {@code glBufferSubData}, no transfer + barrier. Otherwise they go into one reusable staging
 * buffer and are uploaded with {@code CommandEncoder.writeToBuffer} before any render pass opens.
 * Direct writes are safe because {@code FRAMES} (4) exceeds the frames either backend keeps in
 * flight (Vulkan: {@code VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT} = 2), so a slot is idle on
 * the GPU before it is rewritten.
 * A buffer may be drawn from again in later frames as long as nothing is committed in
 * between (frame-plan reuse). Render thread only.
 */
final class IndirectBufferRing {
	private static final int FRAMES = 4;
	private static final int MIN_BYTES = 64 * 1024;

	private final GpuBuffer[] buffers = new GpuBuffer[FRAMES];
	private @Nullable GpuDevice device;
	private boolean mapped;
	private int frame;
	private ByteBuffer staging = ByteBuffer.allocateDirect(MIN_BYTES).order(ByteOrder.nativeOrder());

	/** Uploads {@code commands} into the next ring slot; returns null when there are none. */
	@Nullable GpuBuffer upload(IndirectCommandList commands) {
		int bytes = commands.size() * IndirectCommandList.BYTES_PER_COMMAND;
		if (bytes == 0) {
			return null;
		}
		commands.writeTo(staging(bytes), 0);
		return commit(bytes);
	}

	/**
	 * Native-order staging buffer with at least {@code bytes} capacity. Fill it with absolute
	 * writes, then {@link #commit} the same byte count; the contents are only valid until then.
	 */
	ByteBuffer staging(int bytes) {
		if (staging.capacity() < bytes) {
			staging = ByteBuffer.allocateDirect((int) capacityFor(bytes)).order(ByteOrder.nativeOrder());
		}
		staging.clear().limit(bytes);
		return staging;
	}

	/** Uploads the first {@code bytes} of the staging buffer into the next ring slot; null when empty. */
	@Nullable GpuBuffer commit(int bytes) {
		if (bytes == 0) {
			return null;
		}
		GpuBuffer buffer = next(bytes);
		staging.clear().limit(bytes);
		if (mapped) {
			try (GpuBufferSlice.MappedView view = buffer.map(0L, bytes, false, true)) {
				view.data().put(0, staging, 0, bytes);
			}
		} else {
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, bytes), staging);
		}
		return buffer;
	}

	/**
	 * True when ring buffers are host-mappable (persistent mapping on OpenGL or Vulkan): fill
	 * {@link #next} through {@code GpuBuffer.map} instead of {@link #staging} / {@link #commit}.
	 */
	boolean mapsDirectly() {
		syncDevice(RenderSystem.getDevice());
		return mapped;
	}

	/**
	 * Advances to the next ring slot and returns its buffer with at least {@code bytes}
	 * capacity. Its contents are only stable once the frames that drew from it are done,
	 * which the ring depth guarantees.
	 */
	GpuBuffer next(int bytes) {
		GpuDevice current = RenderSystem.getDevice();
		syncDevice(current);
		frame = (frame + 1) % FRAMES;
		GpuBuffer buffer = buffers[frame];
		if (buffer == null || buffer.isClosed() || buffer.size() < bytes) {
			if (buffer != null && !buffer.isClosed()) {
				buffer.close();
			}
			long capacity = capacityFor(bytes);
			int usage = GpuBuffer.USAGE_INDIRECT_PARAMETERS | (mapped ? GpuBuffer.USAGE_MAP_WRITE : GpuBuffer.USAGE_COPY_DST);
			buffer = current.createBuffer(() -> "Ephemera terrain indirect draws", usage, capacity);
			buffers[frame] = buffer;
		}
		return buffer;
	}

	private void syncDevice(GpuDevice current) {
		if (current != device) {
			// Buffers of a previous device died with it.
			java.util.Arrays.fill(buffers, null);
			device = current;
			mapped = current.getDeviceInfo().features().persistentMapping();
		}
	}

	void close() {
		for (int i = 0; i < FRAMES; i++) {
			GpuBuffer buffer = buffers[i];
			if (buffer != null && !buffer.isClosed() && device == RenderSystem.tryGetDevice()) {
				buffer.close();
			}
			buffers[i] = null;
		}
		if (staging.capacity() > MIN_BYTES * 4) {
			staging = ByteBuffer.allocateDirect(MIN_BYTES).order(ByteOrder.nativeOrder());
		}
	}

	private static long capacityFor(int bytes) {
		return Math.max(MIN_BYTES, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
	}
}
