package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import static org.joml.Math.fma;

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
		if (address == 0L) {
			return false;
		}
		int properties = matrix.properties();
		int packedColor = FastVertexBuffer.packColor(color);
		int stride = layout.stride;
		long a1 = address + stride;
		long a2 = a1 + stride;
		long a3 = a2 + stride;

		if ((properties & Matrix4fc.PROPERTY_IDENTITY) != 0) {
			FastVertexBuffer.position(address, ax, ay, z);
			FastVertexBuffer.position(a1, bx, by, z);
			FastVertexBuffer.position(a2, cx, cy, z);
			FastVertexBuffer.position(a3, dx, dy, z);
		} else if ((properties & Matrix4fc.PROPERTY_TRANSLATION) != 0) {
			float tx = matrix.m30(), ty = matrix.m31(), tz = matrix.m32() + z;
			FastVertexBuffer.position(address, ax + tx, ay + ty, tz);
			FastVertexBuffer.position(a1, bx + tx, by + ty, tz);
			FastVertexBuffer.position(a2, cx + tx, cy + ty, tz);
			FastVertexBuffer.position(a3, dx + tx, dy + ty, tz);
		} else {
			float m00 = matrix.m00(), m01 = matrix.m01(), m02 = matrix.m02();
			float m10 = matrix.m10(), m11 = matrix.m11(), m12 = matrix.m12();
			float tz0 = fma(matrix.m20(), z, matrix.m30());
			float tz1 = fma(matrix.m21(), z, matrix.m31());
			float tz2 = fma(matrix.m22(), z, matrix.m32());
			FastVertexBuffer.position(address, fma(m00, ax, fma(m10, ay, tz0)), fma(m01, ax, fma(m11, ay, tz1)), fma(m02, ax, fma(m12, ay, tz2)));
			FastVertexBuffer.position(a1, fma(m00, bx, fma(m10, by, tz0)), fma(m01, bx, fma(m11, by, tz1)), fma(m02, bx, fma(m12, by, tz2)));
			FastVertexBuffer.position(a2, fma(m00, cx, fma(m10, cy, tz0)), fma(m01, cx, fma(m11, cy, tz1)), fma(m02, cx, fma(m12, cy, tz2)));
			FastVertexBuffer.position(a3, fma(m00, dx, fma(m10, dy, tz0)), fma(m01, dx, fma(m11, dy, tz1)), fma(m02, dx, fma(m12, dy, tz2)));
		}

		if (layout == FastVertexLayout.UI_TEXT) {
			FastVertexBuffer.attributesUiText(address, u0, v0, packedColor);
			FastVertexBuffer.attributesUiText(a1, u0, v1, packedColor);
			FastVertexBuffer.attributesUiText(a2, u1, v1, packedColor);
			FastVertexBuffer.attributesUiText(a3, u1, v0, packedColor);
		} else {
			int packedLight = FastVertexBuffer.packUv(light);
			if (layout == FastVertexLayout.WORLD_TEXT) {
				FastVertexBuffer.attributesWorldText(address, u0, v0, packedLight, packedColor);
				FastVertexBuffer.attributesWorldText(a1, u0, v1, packedLight, packedColor);
				FastVertexBuffer.attributesWorldText(a2, u1, v1, packedLight, packedColor);
				FastVertexBuffer.attributesWorldText(a3, u1, v0, packedLight, packedColor);
			} else {
				FastVertexBuffer.attributesBlock(address, packedColor, u0, v0, packedLight);
				FastVertexBuffer.attributesBlock(a1, packedColor, u0, v1, packedLight);
				FastVertexBuffer.attributesBlock(a2, packedColor, u1, v1, packedLight);
				FastVertexBuffer.attributesBlock(a3, packedColor, u1, v0, packedLight);
			}
		}
		FastVertexBuffer.finish(builder, a3, 4);
		return true;
	}
}
