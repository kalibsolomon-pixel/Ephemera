package com.ephemera.core.terrain;

/**
 * Packs section coordinates into 16x8x16-section spatial regions (256x128x256 blocks).
 * A region key is a single {@code long}; a section's slot inside its region is a
 * dense 0..2047 index, suitable for indexing flat per-region arrays.
 *
 * <p>Block-space helpers give the region origin a section's vertices are baked against.
 * They mask with two's-complement arithmetic, so negative coordinates floor toward
 * negative infinity exactly like {@code Math.floorDiv} (e.g. block {@code -16} is in the
 * region starting at {@code -256}).
 */
public final class RegionKey {
	public static final int WIDTH_SHIFT = 4;
	public static final int HEIGHT_SHIFT = 3;
	public static final int WIDTH = 1 << WIDTH_SHIFT;
	public static final int HEIGHT = 1 << HEIGHT_SHIFT;
	public static final int SECTIONS_PER_REGION = WIDTH * HEIGHT * WIDTH;

	/** Region extent in blocks along X and Z. */
	public static final int BLOCK_WIDTH = WIDTH << 4;
	/** Region extent in blocks along Y. */
	public static final int BLOCK_HEIGHT = HEIGHT << 4;

	private static final int XZ_BITS = 24;
	private static final int Y_BITS = 16;
	private static final long XZ_MASK = (1L << XZ_BITS) - 1;
	private static final long Y_MASK = (1L << Y_BITS) - 1;

	private RegionKey() {}

	/** Region key containing the given section coordinates. */
	public static long fromSection(int sectionX, int sectionY, int sectionZ) {
		return pack(sectionX >> WIDTH_SHIFT, sectionY >> HEIGHT_SHIFT, sectionZ >> WIDTH_SHIFT);
	}

	/** Region key containing the given block coordinates. */
	public static long fromBlock(int blockX, int blockY, int blockZ) {
		return fromSection(blockX >> 4, blockY >> 4, blockZ >> 4);
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

	/** Dense 0..2047 slot of a section inside its region (x fastest, then z, then y). */
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

	/** Minimum block X of the region containing block X {@code blockX}. */
	public static int originBlockX(int blockX) {
		return blockX & -BLOCK_WIDTH;
	}

	public static int originBlockY(int blockY) {
		return blockY & -BLOCK_HEIGHT;
	}

	public static int originBlockZ(int blockZ) {
		return blockZ & -BLOCK_WIDTH;
	}
}
