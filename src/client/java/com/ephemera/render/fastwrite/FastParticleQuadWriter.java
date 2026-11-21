package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;

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
		if (address == 0L) {
			return false;
		}

		// Match Quaternionf.transform, including normalization of non-unit quaternions.
		// cornerZ is always 0, so only the X and Y rotated basis vectors (pre-scaled by size) are needed.
		float xx = qx * qx, yy = qy * qy, zz = qz * qz, ww = qw * qw;
		float xy = qx * qy, xz = qx * qz, yz = qy * qz;
		float xw = qx * qw, yw = qy * qw, zw = qz * qw;
		float s = size / (xx + yy + zz + ww);
		float s2 = 2.0f * s;
		float rx = (xx - yy - zz + ww) * s;
		float ry = (xy + zw) * s2;
		float rz = (xz - yw) * s2;
		float ux = (xy - zw) * s2;
		float uy = (yy - xx - zz + ww) * s;
		float uz = (yz + xw) * s2;

		int packedColor = FastVertexBuffer.packColor(color);
		int packedLight = FastVertexBuffer.packUv(light);
		int stride = layout.stride;

		// Corner 0: (+1, -1) -> (u1, v1)
		FastVertexBuffer.position(address, x + rx - ux, y + ry - uy, z + rz - uz);
		FastVertexBuffer.attributesParticle(address, u1, v1, packedColor, packedLight);
		address += stride;

		// Corner 1: (+1, +1) -> (u1, v0)
		FastVertexBuffer.position(address, x + rx + ux, y + ry + uy, z + rz + uz);
		FastVertexBuffer.attributesParticle(address, u1, v0, packedColor, packedLight);
		address += stride;

		// Corner 2: (-1, +1) -> (u0, v0)
		FastVertexBuffer.position(address, x - rx + ux, y - ry + uy, z - rz + uz);
		FastVertexBuffer.attributesParticle(address, u0, v0, packedColor, packedLight);
		address += stride;

		// Corner 3: (-1, -1) -> (u0, v1)
		FastVertexBuffer.position(address, x - rx - ux, y - ry - uy, z - rz - uz);
		FastVertexBuffer.attributesParticle(address, u0, v1, packedColor, packedLight);

		FastVertexBuffer.finish(builder, address, 4);
		return true;
	}
}
