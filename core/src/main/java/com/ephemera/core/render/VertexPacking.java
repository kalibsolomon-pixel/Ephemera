package com.ephemera.core.render;

/** Scalar packing shared by the native vertex emitters. */
public final class VertexPacking {
	private VertexPacking() {}

	public static int rgba(int argb, boolean littleEndian) {
		int abgr = (argb & 0xff00ff00) | (argb >>> 16 & 0xff) | ((argb & 0xff) << 16);
		return littleEndian ? abgr : Integer.reverseBytes(abgr);
	}

	public static int packedShorts(int lowThenHigh, boolean littleEndian) {
		return littleEndian ? lowThenHigh : Integer.rotateLeft(lowThenHigh, 16);
	}

	public static int normal(float x, float y, float z) {
		return normalByte(x) | (normalByte(y) << 8) | (normalByte(z) << 16);
	}

	private static int normalByte(float component) {
		return (int) (Math.clamp(component, -1.0f, 1.0f) * 127.0f) & 0xff;
	}
}
