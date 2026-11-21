package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;

import static org.joml.Math.fma;

public final class FastParticleQuadWriter {
	private FastParticleQuadWriter() {}

	public static boolean write(VertexConsumer consumer, float x, float y, float z, float qx, float qy,
			float qz, float qw, float size, float u0, float u1, float v0, float v1, int color, int light) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout != FastVertexLayout.PARTICLE) {
			return false;
		}
		long address = FastVertexBuffer.reserve(builder, layout, 4);
		if (address == 0) {
			return false;
		}

		// Match Quaternionf.transform, including its normalization of non-unit quaternions.
		float xx = qx * qx, yy = qy * qy, zz = qz * qz, ww = qw * qw;
		float xy = qx * qy, xz = qx * qz, yz = qy * qz;
		float xw = qx * qw, yw = qy * qw, zw = qz * qw;
		float inv = 1.0f / (xx + yy + zz + ww);
		float m00 = (xx - yy - zz + ww) * inv;
		float m10 = 2.0f * (xy - zw) * inv;
		float m20 = 2.0f * (xz + yw) * inv;
		float m01 = 2.0f * (xy + zw) * inv;
		float m11 = (yy - xx - zz + ww) * inv;
		float m21 = 2.0f * (yz - xw) * inv;
		float m02 = 2.0f * (xz - yw) * inv;
		float m12 = 2.0f * (yz + xw) * inv;
		float m22 = (zz - xx - yy + ww) * inv;
		int packedColor = FastVertexBuffer.packColor(color);
		int packedLight = FastVertexBuffer.packUv(light);
		int stride = layout.stride;
		for (int i = 0; i < 4; i++, address += stride) {
			float cornerX = i < 2 ? 1.0f : -1.0f;
			float cornerY = i == 1 || i == 2 ? 1.0f : -1.0f;
			FastVertexBuffer.position(address,
				fma(m00, cornerX, fma(m10, cornerY, m20 * 0.0f)) * size + x,
				fma(m01, cornerX, fma(m11, cornerY, m21 * 0.0f)) * size + y,
				fma(m02, cornerX, fma(m12, cornerY, m22 * 0.0f)) * size + z);
			FastVertexBuffer.attributes(address, layout, packedColor, i < 2 ? u1 : u0,
				cornerY > 0.0f ? v0 : v1, packedLight, 0, 0);
		}
		FastVertexBuffer.finish(builder, address - stride, 4);
		return true;
	}
}
