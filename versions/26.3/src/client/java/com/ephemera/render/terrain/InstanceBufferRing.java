package com.ephemera.render.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/**
 * Four rotating GPU vertex buffers holding 26.3's 16-byte per-instance {@code ChunkSectionInfo}
 * entries ({@code ivec3 ChunkPosition, float ChunkVisibility}) bound to vertex buffer slot 1.
 *
 * <p>With region-relative baking, settled sections in the same 16x8x16-section region share a
 * single 16-byte entry, keeping uploads tiny (typically &lt; 1 KB) and enabling zero-upload reuse
 * across steady frames. Persistent mapping writes straight into mapped memory without a staging
 * copy or transfer barrier.
 */
final class InstanceBufferRing {
	static final int BYTES_PER_ENTRY = 16;
	private static final int FRAMES = 4;
	private static final int MIN_BYTES = 4096;

	private final GpuBuffer[] buffers = new GpuBuffer[FRAMES];
	private @Nullable GpuDevice device;
	private boolean mapped;
	private int frame;
	private ByteBuffer staging = ByteBuffer.allocateDirect(MIN_BYTES).order(ByteOrder.nativeOrder());

	/** Uploads {@code count} packed {@code (x, y, z, visibility)} entries into the next ring slot. */
	@Nullable GpuBufferSlice upload(int[] xs, int[] ys, int[] zs, float[] vis, int count) {
		int bytes = count * BYTES_PER_ENTRY;
		if (bytes <= 0) {
			return null;
		}
		GpuDevice current = RenderSystem.getDevice();
		syncDevice(current);
		GpuBuffer buffer = next(current, bytes);
		if (mapped) {
			try (GpuBufferSlice.MappedView view = buffer.map(0L, bytes, false, true)) {
				writeEntries(view.data(), xs, ys, zs, vis, count);
			}
		} else {
			if (staging.capacity() < bytes) {
				staging = ByteBuffer.allocateDirect((int) capacityFor(bytes)).order(ByteOrder.nativeOrder());
			}
			staging.clear().limit(bytes);
			writeEntries(staging, xs, ys, zs, vis, count);
			staging.position(0).limit(bytes);
			current.createCommandEncoder().writeToBuffer(buffer.slice(0L, bytes), staging);
		}
		return buffer.slice(0L, bytes);
	}

	private static void writeEntries(ByteBuffer out, int[] xs, int[] ys, int[] zs, float[] vis, int count) {
		int p = 0;
		for (int i = 0; i < count; i++, p += BYTES_PER_ENTRY) {
			out.putInt(p, xs[i]);
			out.putInt(p + 4, ys[i]);
			out.putInt(p + 8, zs[i]);
			out.putFloat(p + 12, vis[i]);
		}
	}

	private GpuBuffer next(GpuDevice current, int bytes) {
		frame = (frame + 1) % FRAMES;
		GpuBuffer buffer = buffers[frame];
		if (buffer == null || buffer.isClosed() || buffer.size() < bytes) {
			if (buffer != null && !buffer.isClosed()) {
				buffer.close();
			}
			long capacity = capacityFor(bytes);
			int usage = GpuBuffer.USAGE_VERTEX | (mapped ? GpuBuffer.USAGE_MAP_WRITE : GpuBuffer.USAGE_COPY_DST);
			buffer = current.createBuffer(() -> "Ephemera terrain chunk instances", usage, capacity);
			buffers[frame] = buffer;
		}
		return buffer;
	}

	private void syncDevice(GpuDevice current) {
		if (current != device) {
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
		if (staging.capacity() > MIN_BYTES * 8) {
			staging = ByteBuffer.allocateDirect(MIN_BYTES).order(ByteOrder.nativeOrder());
		}
	}

	private static long capacityFor(int bytes) {
		return Math.max(MIN_BYTES, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
	}
}
