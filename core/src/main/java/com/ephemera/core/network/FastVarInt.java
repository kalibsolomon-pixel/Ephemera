package com.ephemera.core.network;

/**
 * Pure Java, branchless/table-driven VarInt and VarLong size calculation and
 * multi-byte VarInt packing helpers matching Minecraft's unsigned-7-bit-per-byte
 * LEB128 wire format bit-for-bit.
 */
public final class FastVarInt {
	private static final int[] VARINT_EXACT_BYTE_LENGTHS = new int[33];
	private static final int[] VARLONG_EXACT_BYTE_LENGTHS = new int[65];

	static {
		for (int i = 0; i <= 32; i++) {
			VARINT_EXACT_BYTE_LENGTHS[i] = (int) Math.ceil((31.0 - (i - 1)) / 7.0);
		}
		VARINT_EXACT_BYTE_LENGTHS[32] = 1; // value == 0

		for (int i = 0; i <= 64; i++) {
			VARLONG_EXACT_BYTE_LENGTHS[i] = (int) Math.ceil((63.0 - (i - 1)) / 7.0);
		}
		VARLONG_EXACT_BYTE_LENGTHS[64] = 1; // value == 0L
	}

	private FastVarInt() {}

	/**
	 * Returns the exact encoded byte length (1..5) of a 32-bit VarInt using a single
	 * {@code lzcnt} instruction and a 33-entry lookup table.
	 */
	public static int varIntByteSize(int value) {
		return VARINT_EXACT_BYTE_LENGTHS[Integer.numberOfLeadingZeros(value)];
	}

	/**
	 * Returns the exact encoded byte length (1..10) of a 64-bit VarLong using a single
	 * {@code lzcnt} instruction and a 65-entry lookup table.
	 */
	public static int varLongByteSize(long value) {
		return VARLONG_EXACT_BYTE_LENGTHS[Long.numberOfLeadingZeros(value)];
	}

	/**
	 * Packs a 2-byte VarInt ({@code (value & ~0x3FFF) == 0 && (value & ~0x7F) != 0})
	 * into big-endian short order for {@code ByteBuf.writeShort}.
	 */
	public static int packVarInt2(int value) {
		return ((value & 0x7F | 0x80) << 8) | (value >>> 7);
	}

	/**
	 * Packs a 3-byte VarInt ({@code (value & ~0x1FFFFF) == 0})
	 * into big-endian 24-bit order for {@code ByteBuf.writeMedium}.
	 */
	public static int packVarInt3(int value) {
		return ((value & 0x7F | 0x80) << 16)
			| ((((value >>> 7) & 0x7F) | 0x80) << 8)
			| (value >>> 14);
	}

	/**
	 * Packs a 4-byte VarInt ({@code (value & ~0x0FFFFFFF) == 0})
	 * into big-endian int order for {@code ByteBuf.writeInt}.
	 */
	public static int packVarInt4(int value) {
		return ((value & 0x7F | 0x80) << 24)
			| ((((value >>> 7) & 0x7F) | 0x80) << 16)
			| ((((value >>> 14) & 0x7F) | 0x80) << 8)
			| (value >>> 21);
	}

	/**
	 * Packs the first 4 bytes of a 5-byte VarInt into big-endian int order for
	 * {@code ByteBuf.writeInt}; the 5th byte is {@code value >>> 28}.
	 */
	public static int packVarInt5Prefix(int value) {
		return ((value & 0x7F | 0x80) << 24)
			| ((((value >>> 7) & 0x7F) | 0x80) << 16)
			| ((((value >>> 14) & 0x7F) | 0x80) << 8)
			| (((value >>> 21) & 0x7F) | 0x80);
	}
}
