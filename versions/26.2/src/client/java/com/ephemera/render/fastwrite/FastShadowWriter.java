package com.ephemera.render.fastwrite;

import com.ephemera.core.render.VertexPacking;
import com.ephemera.render.culling.FastShadowCache;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

import static org.joml.Math.fma;

/** Direct native-memory writer for {@link ShadowFeatureRenderer}, eliminating 4 Vector3f allocations and addVertex calls per piece. */
public final class FastShadowWriter {
	private static final int SHADOW_LIGHT = 15728880;
	private static final int PACKED_OVERLAY = FastVertexBuffer.packUv(OverlayTexture.NO_OVERLAY);
	private static final int PACKED_LIGHT = FastVertexBuffer.packUv(SHADOW_LIGHT);
	private static final int PACKED_UP_NORMAL = VertexPacking.normal(0.0f, 1.0f, 0.0f);

	private FastShadowWriter() {}

	public static boolean write(ShadowFeatureRenderer.Submit submit, VertexConsumer consumer) {
		List<EntityRenderState.ShadowPiece> pieces = submit.pieces();
		int count = pieces.size();
		if (count == 0) {
			return true;
		}
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout == null || !layout.model) {
			return false;
		}
		long address = FastVertexBuffer.reserve(builder, layout, count * 4);
		if (address == 0) {
			return false;
		}
		Matrix4fc pose = submit.pose();
		int props = pose.properties();
		boolean identity = (props & Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = !identity && (props & Matrix4fc.PROPERTY_TRANSLATION) != 0;
		float m00 = 0, m01 = 0, m02 = 0, m10 = 0, m11 = 0, m12 = 0, m20 = 0, m21 = 0, m22 = 0;
		float m30 = 0, m31 = 0, m32 = 0;
		if (!identity) {
			m30 = pose.m30(); m31 = pose.m31(); m32 = pose.m32();
			if (!translation) {
				m00 = pose.m00(); m01 = pose.m01(); m02 = pose.m02();
				m10 = pose.m10(); m11 = pose.m11(); m12 = pose.m12();
				m20 = pose.m20(); m21 = pose.m21(); m22 = pose.m22();
			}
		}
		float invScale = -0.5f / submit.radius();
		int stride = layout.stride;
		boolean isEntity = layout == FastVertexLayout.ENTITY;
		for (int i = 0; i < count; i++) {
			EntityRenderState.ShadowPiece piece = pieces.get(i);
			AABB bounds = FastShadowCache.bounds(piece.shapeBelow());
			float rx = piece.relativeX();
			float ry = piece.relativeY();
			float rz = piece.relativeZ();
			float minX = rx + (float) bounds.minX;
			float maxX = rx + (float) bounds.maxX;
			float minY = ry + (float) bounds.minY;
			float minZ = rz + (float) bounds.minZ;
			float maxZ = rz + (float) bounds.maxZ;
			float u0 = fma(minX, invScale, 0.5f);
			float u1 = fma(maxX, invScale, 0.5f);
			float v0 = fma(minZ, invScale, 0.5f);
			float v1 = fma(maxZ, invScale, 0.5f);
			int packedColor = FastVertexBuffer.packColor(ARGB.white(piece.alpha()));

			if (identity) {
				putVertex(address, layout, isEntity, minX, minY, minZ, packedColor, u0, v0);
				address += stride;
				putVertex(address, layout, isEntity, minX, minY, maxZ, packedColor, u0, v1);
				address += stride;
				putVertex(address, layout, isEntity, maxX, minY, maxZ, packedColor, u1, v1);
				address += stride;
				putVertex(address, layout, isEntity, maxX, minY, minZ, packedColor, u1, v0);
				address += stride;
			} else if (translation) {
				float tx0 = minX + m30, tx1 = maxX + m30, ty = minY + m31, tz0 = minZ + m32, tz1 = maxZ + m32;
				putVertex(address, layout, isEntity, tx0, ty, tz0, packedColor, u0, v0);
				address += stride;
				putVertex(address, layout, isEntity, tx0, ty, tz1, packedColor, u0, v1);
				address += stride;
				putVertex(address, layout, isEntity, tx1, ty, tz1, packedColor, u1, v1);
				address += stride;
				putVertex(address, layout, isEntity, tx1, ty, tz0, packedColor, u1, v0);
				address += stride;
			} else {
				// All 4 corners share minY, and pairs share minX/maxX and minZ/maxZ.
				float yx = fma(m10, minY, m30);
				float yy = fma(m11, minY, m31);
				float yz = fma(m12, minY, m32);
				float x0x = fma(m00, minX, yx), x0y = fma(m01, minX, yy), x0z = fma(m02, minX, yz);
				float x1x = fma(m00, maxX, yx), x1y = fma(m01, maxX, yy), x1z = fma(m02, maxX, yz);
				float z0x = m20 * minZ, z0y = m21 * minZ, z0z = m22 * minZ;
				float z1x = m20 * maxZ, z1y = m21 * maxZ, z1z = m22 * maxZ;
				putVertex(address, layout, isEntity, x0x + z0x, x0y + z0y, x0z + z0z, packedColor, u0, v0);
				address += stride;
				putVertex(address, layout, isEntity, x0x + z1x, x0y + z1y, x0z + z1z, packedColor, u0, v1);
				address += stride;
				putVertex(address, layout, isEntity, x1x + z1x, x1y + z1y, x1z + z1z, packedColor, u1, v1);
				address += stride;
				putVertex(address, layout, isEntity, x1x + z0x, x1y + z0y, x1z + z0z, packedColor, u1, v0);
				address += stride;
			}
		}
		FastVertexBuffer.finish(builder, address - stride, count * 4);
		return true;
	}

	private static void putVertex(long address, FastVertexLayout layout, boolean isEntity,
			float x, float y, float z, int packedColor, float u, float v) {
		FastVertexBuffer.position(address, x, y, z);
		if (isEntity) {
			FastVertexBuffer.attributesEntity(address, packedColor, u, v, PACKED_OVERLAY, PACKED_LIGHT, PACKED_UP_NORMAL);
		} else {
			FastVertexBuffer.attributes(address, layout, packedColor, u, v, PACKED_LIGHT, PACKED_OVERLAY, PACKED_UP_NORMAL);
		}
	}
}
