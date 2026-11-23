package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import static org.joml.Math.fma;

public final class FastGlyphWriter {
	/** Accessor implemented by {@code AuroraSdfCellMixin} on Aurora's {@code SdfAtlas$Cell}. */
	public interface SdfCell {
		float ephemera$u0();
		float ephemera$u1();
		float ephemera$v0();
		float ephemera$v1();
	}

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

	/**
	 * Single-reservation batched fast path for a full SDF glyph (1, 2, or 4 quads covering shadow + main + bold).
	 * Hoists buffer access, layout validation, matrix properties, and lightmap packing once per glyph.
	 */
	public static boolean writeSdfGlyph(VertexConsumer consumer, Matrix4fc matrix, int light,
			float penX, float penY, float quadLeft, float quadTop, float quadRight, float quadBottom,
			float boldOffset, float shadowOffset, int color, int shadowColor, boolean bold, boolean italic,
			float u0, float u1, float v0, float v1) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if ((layout != FastVertexLayout.COLOR_FIRST_TEXT && layout != FastVertexLayout.UI_TEXT
				&& layout != FastVertexLayout.WORLD_TEXT) || matrix == null) {
			return false;
		}
		boolean hasShadow = shadowColor != 0;
		int quadCount = (hasShadow ? 2 : 1) * (bold ? 2 : 1);
		int vertCount = quadCount << 2;
		long address = FastVertexBuffer.reserve(builder, layout, vertCount);
		if (address == 0L) {
			return false;
		}
		float topShift = italic ? (1.0f - 0.25f * quadTop) : 0.0f;
		float bottomShift = italic ? (1.0f - 0.25f * quadBottom) : 0.0f;
		int properties = matrix.properties();
		boolean identity = (properties & Matrix4fc.PROPERTY_IDENTITY) != 0;
		boolean translation = !identity && (properties & Matrix4fc.PROPERTY_TRANSLATION) != 0;
		float m00 = 0, m01 = 0, m02 = 0, m10 = 0, m11 = 0, m12 = 0, m30 = 0, m31 = 0, m32 = 0;
		if (!identity) {
			m30 = matrix.m30(); m31 = matrix.m31(); m32 = matrix.m32();
			if (!translation) {
				m00 = matrix.m00(); m01 = matrix.m01(); m02 = matrix.m02();
				m10 = matrix.m10(); m11 = matrix.m11(); m12 = matrix.m12();
			}
		}
		int stride = layout.stride;
		int packedLight = layout == FastVertexLayout.UI_TEXT ? 0 : FastVertexBuffer.packUv(light);
		long cursor = address;
		if (hasShadow) {
			int packedShadow = FastVertexBuffer.packColor(shadowColor);
			float sx = penX + shadowOffset;
			float sy = penY + shadowOffset;
			cursor = emitSdfQuad(cursor, stride, layout, identity, translation,
				m00, m01, m02, m10, m11, m12, m30, m31, m32,
				sx + quadLeft, sy + quadTop, sx + quadRight, sy + quadBottom,
				topShift, bottomShift, u0, u1, v0, v1, packedShadow, packedLight);
			if (bold) {
				float sbx = sx + boldOffset;
				cursor = emitSdfQuad(cursor, stride, layout, identity, translation,
					m00, m01, m02, m10, m11, m12, m30, m31, m32,
					sbx + quadLeft, sy + quadTop, sbx + quadRight, sy + quadBottom,
					topShift, bottomShift, u0, u1, v0, v1, packedShadow, packedLight);
			}
		}
		int packedColor = FastVertexBuffer.packColor(color);
		cursor = emitSdfQuad(cursor, stride, layout, identity, translation,
			m00, m01, m02, m10, m11, m12, m30, m31, m32,
			penX + quadLeft, penY + quadTop, penX + quadRight, penY + quadBottom,
			topShift, bottomShift, u0, u1, v0, v1, packedColor, packedLight);
		if (bold) {
			float bx = penX + boldOffset;
			cursor = emitSdfQuad(cursor, stride, layout, identity, translation,
				m00, m01, m02, m10, m11, m12, m30, m31, m32,
				bx + quadLeft, penY + quadTop, bx + quadRight, penY + quadBottom,
				topShift, bottomShift, u0, u1, v0, v1, packedColor, packedLight);
		}
		FastVertexBuffer.finish(builder, cursor - stride, vertCount);
		return true;
	}

	private static long emitSdfQuad(long address, int stride, FastVertexLayout layout,
			boolean identity, boolean translation,
			float m00, float m01, float m02, float m10, float m11, float m12, float m30, float m31, float m32,
			float x0, float y0, float x1, float y1, float topShift, float bottomShift,
			float u0, float u1, float v0, float v1, int packedColor, int packedLight) {
		float ax = x0 + topShift;
		float bx = x0 + bottomShift;
		float cx = x1 + bottomShift;
		float dx = x1 + topShift;
		long a1 = address + stride;
		long a2 = a1 + stride;
		long a3 = a2 + stride;
		if (identity) {
			FastVertexBuffer.position(address, ax, y0, 0.0f);
			FastVertexBuffer.position(a1, bx, y1, 0.0f);
			FastVertexBuffer.position(a2, cx, y1, 0.0f);
			FastVertexBuffer.position(a3, dx, y0, 0.0f);
		} else if (translation) {
			FastVertexBuffer.position(address, ax + m30, y0 + m31, m32);
			FastVertexBuffer.position(a1, bx + m30, y1 + m31, m32);
			FastVertexBuffer.position(a2, cx + m30, y1 + m31, m32);
			FastVertexBuffer.position(a3, dx + m30, y0 + m31, m32);
		} else {
			FastVertexBuffer.position(address, fma(m00, ax, fma(m10, y0, m30)), fma(m01, ax, fma(m11, y0, m31)), fma(m02, ax, fma(m12, y0, m32)));
			FastVertexBuffer.position(a1, fma(m00, bx, fma(m10, y1, m30)), fma(m01, bx, fma(m11, y1, m31)), fma(m02, bx, fma(m12, y1, m32)));
			FastVertexBuffer.position(a2, fma(m00, cx, fma(m10, y1, m30)), fma(m01, cx, fma(m11, y1, m31)), fma(m02, cx, fma(m12, y1, m32)));
			FastVertexBuffer.position(a3, fma(m00, dx, fma(m10, y0, m30)), fma(m01, dx, fma(m11, y0, m31)), fma(m02, dx, fma(m12, y0, m32)));
		}
		if (layout == FastVertexLayout.COLOR_FIRST_TEXT) {
			FastVertexBuffer.attributesBlock(address, packedColor, u0, v0, packedLight);
			FastVertexBuffer.attributesBlock(a1, packedColor, u0, v1, packedLight);
			FastVertexBuffer.attributesBlock(a2, packedColor, u1, v1, packedLight);
			FastVertexBuffer.attributesBlock(a3, packedColor, u1, v0, packedLight);
		} else if (layout == FastVertexLayout.UI_TEXT) {
			FastVertexBuffer.attributesUiText(address, u0, v0, packedColor);
			FastVertexBuffer.attributesUiText(a1, u0, v1, packedColor);
			FastVertexBuffer.attributesUiText(a2, u1, v1, packedColor);
			FastVertexBuffer.attributesUiText(a3, u1, v0, packedColor);
		} else {
			FastVertexBuffer.attributesWorldText(address, u0, v0, packedLight, packedColor);
			FastVertexBuffer.attributesWorldText(a1, u0, v1, packedLight, packedColor);
			FastVertexBuffer.attributesWorldText(a2, u1, v1, packedLight, packedColor);
			FastVertexBuffer.attributesWorldText(a3, u1, v0, packedLight, packedColor);
		}
		return a3 + stride;
	}

	/** Fast path for pre-sheared SDF text quads (e.g. Aurora Client's {@code SdfInstance}). */
	public static boolean writeSdfQuad(VertexConsumer consumer, Matrix4fc matrix, int light, int argb,
			float x0, float y0, float x1, float y1, float topShift, float bottomShift,
			float u0, float u1, float v0, float v1) {
		return write(consumer, matrix, 0.0f,
			x0 + topShift, y0, x0 + bottomShift, y1, x1 + bottomShift, y1, x1 + topShift, y0,
			u0, u1, v0, v1, argb, light);
	}

	private static boolean write(VertexConsumer consumer, Matrix4fc matrix, float z,
			float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy,
			float u0, float u1, float v0, float v1, int color, int light) {
		FastBufferState builder = FastVertexBuffer.access(consumer);
		FastVertexLayout layout = FastVertexBuffer.layout(builder);
		if ((layout != FastVertexLayout.UI_TEXT && layout != FastVertexLayout.WORLD_TEXT
				&& layout != FastVertexLayout.COLOR_FIRST_TEXT) || matrix == null) {
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
