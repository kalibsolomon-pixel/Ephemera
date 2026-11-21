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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

public final class FastVertexBuffer {
	private static final int MAX_VERTICES = 0xff_ffff;
	private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	private static volatile Backend activeBackend = new Backend(null, 0);

	private FastVertexBuffer() {}

	public interface SpriteExpander {
		VertexConsumer ephemera$delegate();
		TextureAtlasSprite ephemera$sprite();
	}

	public interface SheetedDecal {
		VertexConsumer ephemera$delegate();
		Matrix4f ephemera$cameraInversePose();
		Matrix3f ephemera$normalInversePose();
		float ephemera$textureScale();
	}

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
		Backend cached = activeBackend;
		if (device != cached.device()) {
			int type = device == null ? 0 : switch (device.getDeviceInfo().backendName()) {
				case "OpenGL" -> 1;
				case "Vulkan" -> 2;
				default -> 0;
			};
			cached = new Backend(device, type);
			activeBackend = cached;
		}
		return cached.type() == 1 ? config.backendGlDispatchCache : cached.type() == 2 && config.backendVulkanDispatchCache;
	}

	private record Backend(GpuDevice device, int type) {}

	static long reserve(FastBufferState builder, FastVertexLayout layout, int count) {
		if (!canAppend(builder, count)) {
			return 0;
		}
		ByteBufferBuilder storage = builder.ephemera$buffer();
		if (!(storage instanceof FastBufferState.Storage checkedStorage)) {
			return 0;
		}
		checkedStorage.ephemera$checkOpen();
		return storage.reserve(count * layout.stride);
	}

	private static boolean canAppend(FastBufferState builder, int count) {
		return builder.ephemera$isBuilding() && builder.ephemera$elementsToFill() == 0
			&& builder.ephemera$vertices() <= MAX_VERTICES - count;
	}

	static void finish(FastBufferState builder, long lastVertex, int count) {
		builder.ephemera$vertexPointer(lastVertex);
		builder.ephemera$vertices(builder.ephemera$vertices() + count);
		// reserve requires an empty element mask, and a full write leaves it empty.
	}

	static int packColor(int color) {
		return VertexPacking.rgba(color, LITTLE_ENDIAN);
	}

	static int packUv(int uv) {
		return VertexPacking.packedShorts(uv, LITTLE_ENDIAN);
	}

	static void position(long address, float x, float y, float z) {
		memPutFloat(address, x);
		memPutFloat(address + 4, y);
		memPutFloat(address + 8, z);
	}

	static void attributes(long address, FastVertexLayout layout, int packedColor,
			float u, float v, int packedLight, int packedOverlay, int normal) {
		if (layout.color >= 0) {
			memPutInt(address + layout.color, packedColor);
		}
		memPutFloat(address + layout.uv, u);
		memPutFloat(address + layout.uv + 4, v);
		if (layout.light >= 0) {
			memPutInt(address + layout.light, packedLight);
		}
		if (layout.overlay >= 0) {
			memPutInt(address + layout.overlay, packedOverlay);
		}
		if (layout.normal >= 0) {
			long normalAddress = address + layout.normal;
			memPutByte(normalAddress, (byte) normal);
			memPutByte(normalAddress + 1, (byte) (normal >>> 8));
			memPutByte(normalAddress + 2, (byte) (normal >>> 16));
		}
	}

	/** Branch-free attribute write for {@link FastVertexLayout#ENTITY} (stride 36). */
	static void attributesEntity(long address, int packedColor, float u, float v,
			int packedOverlay, int packedLight, int normal) {
		memPutInt(address + 12, packedColor);
		memPutFloat(address + 16, u);
		memPutFloat(address + 20, v);
		memPutInt(address + 24, packedOverlay);
		memPutInt(address + 28, packedLight);
		memPutByte(address + 32, (byte) normal);
		memPutByte(address + 33, (byte) (normal >>> 8));
		memPutByte(address + 34, (byte) (normal >>> 16));
	}

	/** Branch-free attribute write for {@link FastVertexLayout#BLOCK} (stride 28). */
	static void attributesBlock(long address, int packedColor, float u, float v, int packedLight) {
		memPutInt(address + 12, packedColor);
		memPutFloat(address + 16, u);
		memPutFloat(address + 20, v);
		memPutInt(address + 24, packedLight);
	}
}
