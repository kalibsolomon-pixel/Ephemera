package com.ephemera.core.network;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FastVarIntTest {
	private static int referenceVarIntSize(int value) {
		for (int i = 1; i < 5; i++) {
			if ((value & (-1 << (i * 7))) == 0) {
				return i;
			}
		}
		return 5;
	}

	private static int referenceVarLongSize(long value) {
		for (int i = 1; i < 10; i++) {
			if ((value & (-1L << (i * 7))) == 0L) {
				return i;
			}
		}
		return 10;
	}

	private static byte[] referenceEncodeVarInt(int value) {
		byte[] tmp = new byte[5];
		int len = 0;
		while ((value & -128) != 0) {
			tmp[len++] = (byte) ((value & 127) | 128);
			value >>>= 7;
		}
		tmp[len++] = (byte) value;
		byte[] out = new byte[len];
		System.arraycopy(tmp, 0, out, 0, len);
		return out;
	}

	private static byte[] fastEncodeVarInt(int value) {
		if ((value & (0xFFFFFFFF << 7)) == 0) {
			return new byte[] { (byte) value };
		} else if ((value & (0xFFFFFFFF << 14)) == 0) {
			int w = FastVarInt.packVarInt2(value);
			return new byte[] { (byte) (w >>> 8), (byte) w };
		} else if ((value & (0xFFFFFFFF << 21)) == 0) {
			int w = FastVarInt.packVarInt3(value);
			return new byte[] { (byte) (w >>> 16), (byte) (w >>> 8), (byte) w };
		} else if ((value & (0xFFFFFFFF << 28)) == 0) {
			int w = FastVarInt.packVarInt4(value);
			return new byte[] { (byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w };
		} else {
			int w = FastVarInt.packVarInt5Prefix(value);
			return new byte[] { (byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w, (byte) (value >>> 28) };
		}
	}

	@Test
	void varIntSizeAndEncodingMatchReferenceOnAllBoundaries() {
		int[] edgeValues = {
			0, 1, 63, 64, 127, 128, 255, 256,
			16383, 16384,
			2097151, 2097152,
			268435455, 268435456,
			Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
			Integer.MIN_VALUE, Integer.MIN_VALUE + 1,
			-1, -2, -127, -128, -16384
		};
		for (int v : edgeValues) {
			assertEquals(referenceVarIntSize(v), FastVarInt.varIntByteSize(v), "size mismatch for " + v);
			assertArrayEquals(referenceEncodeVarInt(v), fastEncodeVarInt(v), "encoding mismatch for " + v);
		}

		// Sweep every bit position and random values
		for (int bit = 0; bit < 32; bit++) {
			int base = 1 << bit;
			for (int delta = -2; delta <= 2; delta++) {
				int v = base + delta;
				assertEquals(referenceVarIntSize(v), FastVarInt.varIntByteSize(v));
				assertArrayEquals(referenceEncodeVarInt(v), fastEncodeVarInt(v));
			}
		}

		Random rnd = new Random(0x5EED1234L);
		for (int i = 0; i < 10_000; i++) {
			int v = rnd.nextInt();
			assertEquals(referenceVarIntSize(v), FastVarInt.varIntByteSize(v));
			assertArrayEquals(referenceEncodeVarInt(v), fastEncodeVarInt(v));
		}
	}

	@Test
	void varLongSizeMatchesReferenceOnAllBoundaries() {
		long[] edgeValues = {
			0L, 1L, 127L, 128L, 16383L, 16384L,
			Long.MAX_VALUE, Long.MAX_VALUE - 1,
			Long.MIN_VALUE, Long.MIN_VALUE + 1,
			-1L, -128L
		};
		for (long v : edgeValues) {
			assertEquals(referenceVarLongSize(v), FastVarInt.varLongByteSize(v), "size mismatch for " + v);
		}
		for (int bit = 0; bit < 64; bit++) {
			long base = 1L << bit;
			for (long delta = -2L; delta <= 2L; delta++) {
				long v = base + delta;
				assertEquals(referenceVarLongSize(v), FastVarInt.varLongByteSize(v));
			}
		}
		Random rnd = new Random(0xCAFEBABEL);
		for (int i = 0; i < 10_000; i++) {
			long v = rnd.nextLong();
			assertEquals(referenceVarLongSize(v), FastVarInt.varLongByteSize(v));
		}
	}
}
