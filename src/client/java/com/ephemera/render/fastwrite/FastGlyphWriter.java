package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
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
				&& layout != FastVertexLayout.COLOR_FIRST_TEXT) || matrix == null || matrix.getClass() != Matrix4f.class) {
			return false;
		}
		long address = FastVertexBuffer.reserve(builder, layout, 4);
		if (address == 0) {
			return false;
		}
		int properties = matrix.properties();
		int packedColor = FastVertexBuffer.packColor(color);
		int packedLight = FastVertexBuffer.packUv(light);
		int stride = layout.stride;
		writeVertex(address, layout, matrix, properties, ax, ay, z, u0, v0, packedColor, packedLight);
		writeVertex(address += stride, layout, matrix, properties, bx, by, z, u0, v1, packedColor, packedLight);
		writeVertex(address += stride, layout, matrix, properties, cx, cy, z, u1, v1, packedColor, packedLight);
		writeVertex(address += stride, layout, matrix, properties, dx, dy, z, u1, v0, packedColor, packedLight);
		FastVertexBuffer.finish(builder, address, 4);
		return true;
	}

	private static void writeVertex(long address, FastVertexLayout layout, Matrix4fc matrix, int properties,
			float x, float y, float z, float u, float v, int packedColor, int packedLight) {
		FastVertexTransforms.position(address, matrix, properties, x, y, z);
		FastVertexBuffer.attributes(address, layout, packedColor, u, v, packedLight, 0, 0);
	}
}
