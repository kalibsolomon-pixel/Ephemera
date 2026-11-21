package com.ephemera.core.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VertexPackingTest {
	@Test
	void argbProducesRgbaBytesOnEitherByteOrder() {
		byte[] expected = {0x12, 0x34, 0x56, (byte) 0x80};
		assertArrayEquals(expected, bytes(VertexPacking.rgba(0x80123456, true), ByteOrder.LITTLE_ENDIAN));
		assertArrayEquals(expected, bytes(VertexPacking.rgba(0x80123456, false), ByteOrder.BIG_ENDIAN));
	}

	@Test
	void lightAndOverlayWriteTheLowShortFirst() {
		int uv = 0xfedc1234;
		assertArrayEquals(new byte[] {0x34, 0x12, (byte) 0xdc, (byte) 0xfe},
			bytes(VertexPacking.packedShorts(uv, true), ByteOrder.LITTLE_ENDIAN));
		assertArrayEquals(new byte[] {0x12, 0x34, (byte) 0xfe, (byte) 0xdc},
			bytes(VertexPacking.packedShorts(uv, false), ByteOrder.BIG_ENDIAN));
	}

	@Test
	void normalsSaturateAndTruncateWithoutRounding() {
		assertEquals(0x3f7f81, VertexPacking.normal(-2, Float.POSITIVE_INFINITY, 0.5f));
		assertEquals(0x0000c1, VertexPacking.normal(-0.5f, Float.NaN, -0.0f));
	}

	private static byte[] bytes(int packed, ByteOrder order) {
		return ByteBuffer.allocate(Integer.BYTES).order(order).putInt(packed).array();
	}
}
