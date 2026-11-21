package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

public final class FastGlyphWriter {
	private FastGlyphWriter() {}

	public static boolean writeGlyph(VertexConsumer consumer, Matrix4fc matrix, boolean italic, boolean bold,
			float x, float y, float z, float left, float right, float up, float down,
			float u0, float u1, float v0, float v1, int color, int light) {
		float topShear = italic ? 1.0f - 0.25f * up : 0.0f;
		float bottomShear = italic ? 1.0f - 0.25f * down : 0.0f;
		float thickness = bold ? 0.1f : 0.0f;
		float minX = x + left;
		float maxX = x + right;
		float top = (y + up) - thickness;
		float bottom = (y + down) + thickness;
		return write(consumer, matrix, z,
			minX + topShear - thickness, top, minX + bottomShear - thickness, bottom,
			maxX + bottomShear + thickness, bottom, maxX + topShear + thickness, top,
			u0, u1, v0, v1, color, light);
	}

	public static boolean writeEffect(VertexConsumer consumer, Matrix4fc matrix, float offset, float depth,
			float x0, float y0, float x1, float y1, float u0, float u1, float v0, float v1, int color, int light) {
		float left = x0 + offset;
		float right = x1 + offset;
		float top = y0 + offset;
		float bottom = y1 + offset;
		return write(consumer, matrix, depth, left, bottom, right, bottom, right, top, left, top,
			u0, u1, v0, v1, color, light);
	}

	private static boolean write(VertexConsumer consumer, Matrix4fc matrix, float z,
			float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy,
			float u0, float u1, float v0, float v1, int color, int light) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if ((layout != FastVertexLayout.UI_TEXT && layout != FastVertexLayout.WORLD_TEXT
				&& layout != FastVertexLayout.COLOR_FIRST_TEXT) || !FastVertexBuffer.canAppend(builder, 4)) {
			return false;
		}
		FastVertexBuffer.Scratch scratch = FastVertexBuffer.acquire();
		if (scratch == null) {
			return false;
		}
		try {
			matrix.transformPosition(ax, ay, z, scratch.vector);
			scratch.vertex(0, color, u0, v0, light, 0);
			matrix.transformPosition(bx, by, z, scratch.vector);
			scratch.vertex(1, color, u0, v1, light, 0);
			matrix.transformPosition(cx, cy, z, scratch.vector);
			scratch.vertex(2, color, u1, v1, light, 0);
			matrix.transformPosition(dx, dy, z, scratch.vector);
			scratch.vertex(3, color, u1, v0, light, 0);
			return scratch.append(builder, layout, 4, 0);
		} finally {
			scratch.release();
		}
	}
}
