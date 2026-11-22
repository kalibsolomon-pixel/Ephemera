package com.ephemera.render.terrain;

import com.ephemera.core.terrain.IndirectCommandList;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Three rotating indirect-parameter buffers, one per frame in flight, so a frame never
 * overwrites commands an earlier frame may still be reading. Each buffer grows lazily to the
 * next power of two; the replaced buffer is closed (both backends defer the actual release
 * until the GPU is done with it). Commands are written straight into one reusable staging
 * buffer and uploaded with {@code CommandEncoder.writeToBuffer} before any render pass opens.
 * A buffer may be drawn from again in later frames as long as nothing is committed in
 * between (frame-plan reuse). Render thread only.
 */
final class IndirectBufferRing {
	private static final int FRAMES = 3;
	private static final int MIN_BYTES = 64 * 1024;

	private final GpuBuffer[] buffers = new GpuBuffer[FRAMES];
	private @Nullable GpuDevice device;
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
		GpuDevice current = RenderSystem.getDevice();
		if (current != device) {
			// Buffers of a previous device died with it.
			java.util.Arrays.fill(buffers, null);
			device = current;
		}
		frame = (frame + 1) % FRAMES;
		GpuBuffer buffer = buffers[frame];
		if (buffer == null || buffer.isClosed() || buffer.size() < bytes) {
			if (buffer != null && !buffer.isClosed()) {
				buffer.close();
			}
			long capacity = capacityFor(bytes);
			buffer = current.createBuffer(() -> "Ephemera terrain indirect draws",
				GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST, capacity);
			buffers[frame] = buffer;
		}
		staging.clear().limit(bytes);
		current.createCommandEncoder().writeToBuffer(buffer.slice(0, bytes), staging);
		return buffer;
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
