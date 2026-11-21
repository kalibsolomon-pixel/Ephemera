package com.ephemera.core.terrain;

/**
 * Packs section coordinates into 8x4x8-section spatial regions (128x64x128 blocks).
 * A region key is a single {@code long}; a section's slot inside its region is a
 * dense 0..255 index, suitable for indexing flat per-region arrays.
 */
public final class RegionKey {
	public static final int WIDTH_SHIFT = 3;
	public static final int HEIGHT_SHIFT = 2;
	public static final int WIDTH = 1 << WIDTH_SHIFT;
	public static final int HEIGHT = 1 << HEIGHT_SHIFT;
	public static final int SECTIONS_PER_REGION = WIDTH * HEIGHT * WIDTH;

	private static final int XZ_BITS = 24;
	private static final int Y_BITS = 16;
	private static final long XZ_MASK = (1L << XZ_BITS) - 1;
	private static final long Y_MASK = (1L << Y_BITS) - 1;

	private RegionKey() {}

	/** Region key containing the given section coordinates. */
	public static long fromSection(int sectionX, int sectionY, int sectionZ) {
		return pack(sectionX >> WIDTH_SHIFT, sectionY >> HEIGHT_SHIFT, sectionZ >> WIDTH_SHIFT);
	}

	public static long pack(int regionX, int regionY, int regionZ) {
		return ((regionX & XZ_MASK) << (XZ_BITS + Y_BITS))
			| ((regionZ & XZ_MASK) << Y_BITS)
			| (regionY & Y_MASK);
	}

	public static int regionX(long key) {
		return (int) (key >> (XZ_BITS + Y_BITS));
	}

	public static int regionZ(long key) {
		return (int) ((key << (64 - XZ_BITS - Y_BITS)) >> (64 - XZ_BITS));
	}

	public static int regionY(long key) {
		return (int) ((key << (64 - Y_BITS)) >> (64 - Y_BITS));
	}

	/** Dense 0..255 slot of a section inside its region (x fastest, then z, then y). */
	public static int localIndex(int sectionX, int sectionY, int sectionZ) {
		return (sectionX & (WIDTH - 1))
			| (sectionZ & (WIDTH - 1)) << WIDTH_SHIFT
			| (sectionY & (HEIGHT - 1)) << (2 * WIDTH_SHIFT);
	}

	/** Minimum section X covered by the region. */
	public static int originSectionX(long key) {
		return regionX(key) << WIDTH_SHIFT;
	}

	public static int originSectionY(long key) {
		return regionY(key) << HEIGHT_SHIFT;
	}

	public static int originSectionZ(long key) {
		return regionZ(key) << WIDTH_SHIFT;
	}
}
