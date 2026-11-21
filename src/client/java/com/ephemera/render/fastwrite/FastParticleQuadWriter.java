package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class FastParticleQuadWriter {
	private FastParticleQuadWriter() {}

	public static boolean write(VertexConsumer consumer, float x, float y, float z, float qx, float qy,
			float qz, float qw, float size, float u0, float u1, float v0, float v1, int color, int light) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if (layout != FastVertexLayout.PARTICLE || !FastVertexBuffer.canAppend(builder, 4)) {
			return false;
		}
		FastVertexBuffer.Scratch scratch = FastVertexBuffer.acquire();
		if (scratch == null) {
			return false;
		}
		try {
			scratch.rotation.set(qx, qy, qz, qw);
			writeCorner(scratch, 0, 1, -1, x, y, z, size, u1, v1, color, light);
			writeCorner(scratch, 1, 1, 1, x, y, z, size, u1, v0, color, light);
			writeCorner(scratch, 2, -1, 1, x, y, z, size, u0, v0, color, light);
			writeCorner(scratch, 3, -1, -1, x, y, z, size, u0, v1, color, light);
			return scratch.append(builder, layout, 4, 0);
		} finally {
			scratch.release();
		}
	}

	private static void writeCorner(FastVertexBuffer.Scratch scratch, int index, float cornerX, float cornerY,
			float x, float y, float z, float size, float u, float v, int color, int light) {
		scratch.vector.set(cornerX, cornerY, 0).rotate(scratch.rotation).mul(size).add(x, y, z);
		scratch.vertex(index, color, u, v, light, 0);
	}
}
