package com.ephemera.render.fastwrite;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.render.VertexPacking;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteOrder;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

final class FastVertexBuffer {
	private static final int MAX_VERTICES = 0xff_ffff;
	private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	private FastVertexBuffer() {}

	static FastBufferState access(VertexConsumer consumer) {
		return consumer != null && consumer.getClass() == BufferBuilder.class
			&& consumer instanceof FastBufferState builder && enabled() ? builder : null;
	}

	static FastVertexLayout layout(FastBufferState builder) {
		return builder instanceof FastBufferLayout formatted ? formatted.ephemera$layout() : null;
	}

	private static boolean enabled() {
		EphemeraConfig config = EphemeraCommon.config();
		if (config.backendGlDispatchCache == config.backendVulkanDispatchCache) {
			return config.backendGlDispatchCache;
		}
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return false;
		}
		return switch (device.getDeviceInfo().backendName()) {
			case "OpenGL" -> config.backendGlDispatchCache;
			case "Vulkan" -> config.backendVulkanDispatchCache;
			default -> false;
		};
	}

	static boolean canAppend(FastBufferState builder, int count) {
		return builder.ephemera$isBuilding() && builder.ephemera$elementsToFill() == 0
			&& builder.ephemera$vertices() <= MAX_VERTICES - count;
	}

	static Scratch acquire() {
		Scratch scratch = SCRATCH.get();
		if (scratch.inUse) {
			return null;
		}
		scratch.inUse = true;
		return scratch;
	}

	static final class Scratch {
		private static final int CUBE_VERTEX_COUNT = 24;
		private static final int FLOATS_PER_VERTEX = 5;

		final Vector3f vector = new Vector3f();
		final Quaternionf rotation = new Quaternionf();
		private final float[] coordinates = new float[CUBE_VERTEX_COUNT * FLOATS_PER_VERTEX];
		private final int[] colors = new int[CUBE_VERTEX_COUNT];
		private final int[] lights = new int[CUBE_VERTEX_COUNT];
		private final int[] normals = new int[CUBE_VERTEX_COUNT];
		private boolean inUse;

		void vertex(int i, int color, float u, float v, int light, int normal) {
			int base = i * FLOATS_PER_VERTEX;
			coordinates[base] = vector.x;
			coordinates[base + 1] = vector.y;
			coordinates[base + 2] = vector.z;
			coordinates[base + 3] = u;
			coordinates[base + 4] = v;
			colors[i] = color;
			lights[i] = light;
			normals[i] = normal;
		}

		boolean append(FastBufferState builder, FastVertexLayout layout, int count, int overlay) {
			if (!canAppend(builder, count)) {
				return false;
			}
			ByteBufferBuilder storage = builder.ephemera$buffer();
			if (!(storage instanceof FastBufferState.Storage checkedStorage)) {
				return false;
			}
			checkedStorage.ephemera$checkOpen();

			int stride = layout.stride;
			long address = storage.reserve(count * stride);
			int packedOverlay = VertexPacking.packedShorts(overlay, LITTLE_ENDIAN);
			for (int i = 0; i < count; i++, address += stride) {
				int offset = i * FLOATS_PER_VERTEX;
				memPutFloat(address, coordinates[offset]);
				memPutFloat(address + 4, coordinates[offset + 1]);
				memPutFloat(address + 8, coordinates[offset + 2]);
				memPutInt(address + layout.color, VertexPacking.rgba(colors[i], LITTLE_ENDIAN));
				memPutFloat(address + layout.uv, coordinates[offset + 3]);
				memPutFloat(address + layout.uv + 4, coordinates[offset + 4]);
				if (layout.light >= 0) {
					memPutInt(address + layout.light, VertexPacking.packedShorts(lights[i], LITTLE_ENDIAN));
				}
				if (layout.overlay >= 0) {
					memPutInt(address + layout.overlay, packedOverlay);
				}
				if (layout.normal >= 0) {
					int normal = normals[i];
					long normalAddress = address + layout.normal;
					memPutByte(normalAddress, (byte) normal);
					memPutByte(normalAddress + 1, (byte) (normal >>> 8));
					memPutByte(normalAddress + 2, (byte) (normal >>> 16));
				}
			}
			builder.ephemera$vertexPointer(address - stride);
			builder.ephemera$vertices(builder.ephemera$vertices() + count);
			builder.ephemera$elementsToFill(0);
			return true;
		}

		void release() {
			inUse = false;
		}
	}
}
