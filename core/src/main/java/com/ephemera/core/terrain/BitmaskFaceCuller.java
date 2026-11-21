package com.ephemera.core.terrain;

/**
 * Flat 18x18x18 occlusion bitmask covering one 16x16x16 section plus a one-block
 * border on every side. Each cell stores a 6-bit mask of which of its faces are
 * fully occluding (a neighbouring face pointing at it is always culled), and a
 * packed "occludes on all six faces" bitset lets {@link #isBuried} reject a fully
 * enclosed block with a single branch.
 *
 * <p>Direction indices follow the common voxel convention used by the client
 * engine: 0=-Y, 1=+Y, 2=-Z, 3=+Z, 4=-X, 5=+X. The opposite of direction {@code d}
 * is {@code d ^ 1}.
 */
public final class BitmaskFaceCuller {
	public static final int SIZE = 18;
	public static final int AREA = SIZE * SIZE;
	public static final int VOLUME = AREA * SIZE;
	public static final int ALL_FACES = 0x3F;

	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;

	/** Index delta for a step in each direction, matching {@link #index}. */
	private static final int[] STEP = {-AREA, AREA, -SIZE, SIZE, -1, 1};

	private final byte[] faceMasks = new byte[VOLUME];
	private final long[] fullCells = new long[(VOLUME + 63) >>> 6];
	private int occludingCells;

	/** Resets every cell to "occludes nothing". */
	public void clear() {
		if (occludingCells == 0) {
			return;
		}
		java.util.Arrays.fill(faceMasks, (byte) 0);
		java.util.Arrays.fill(fullCells, 0L);
		occludingCells = 0;
	}

	/** Whether any cell has been marked as occluding since the last {@link #clear()}. */
	public boolean isEmpty() {
		return occludingCells == 0;
	}

	/**
	 * Flat index of a section-local coordinate. Valid inputs are -1..16 on each axis
	 * (the section plus its one-block border).
	 */
	public static int index(int x, int y, int z) {
		return ((y + 1) * SIZE + (z + 1)) * SIZE + (x + 1);
	}

	/**
	 * Records which faces of the cell at section-local {@code (x, y, z)} fully occlude
	 * their neighbour. {@code faceMask} bit {@code d} means the face pointing in
	 * direction {@code d} is a full, opaque square.
	 */
	public void set(int x, int y, int z, int faceMask) {
		setIndex(index(x, y, z), faceMask);
	}

	public void setIndex(int idx, int faceMask) {
		int mask = faceMask & ALL_FACES;
		int previous = faceMasks[idx];
		if (previous == mask) {
			return;
		}
		faceMasks[idx] = (byte) mask;
		if (previous == 0) {
			occludingCells++;
		} else if (mask == 0) {
			occludingCells--;
		}
		long bit = 1L << (idx & 63);
		if (mask == ALL_FACES) {
			fullCells[idx >>> 6] |= bit;
		} else {
			fullCells[idx >>> 6] &= ~bit;
		}
	}

	public int faceMask(int x, int y, int z) {
		return faceMasks[index(x, y, z)];
	}

	/**
	 * Returns the 6-bit mask of directions in which the neighbour of the section-local
	 * cell {@code (x, y, z)} presents a fully occluding face toward it. Valid inputs are
	 * 0..15 on each axis.
	 */
	public int occludedFaces(int x, int y, int z) {
		int idx = index(x, y, z);
		int result = 0;
		for (int d = 0; d < 6; d++) {
			int neighbourMask = faceMasks[idx + STEP[d]];
			// The neighbour must occlude with its face pointing back at us (opposite direction).
			result |= ((neighbourMask >>> (d ^ 1)) & 1) << d;
		}
		return result;
	}

	/**
	 * True when every face of the section-local cell {@code (x, y, z)} would be culled by
	 * its neighbours. Valid inputs are 0..15 on each axis.
	 */
	public boolean isBuried(int x, int y, int z) {
		int idx = index(x, y, z);
		// Fast path: all six neighbours occlude on every face; one combined test, one branch.
		long all = bit(idx - AREA) & bit(idx + AREA) & bit(idx - SIZE) & bit(idx + SIZE) & bit(idx - 1) & bit(idx + 1);
		if (all != 0L) {
			return true;
		}
		return occludedFaces(x, y, z) == ALL_FACES;
	}

	private long bit(int idx) {
		return (fullCells[idx >>> 6] >>> (idx & 63)) & 1L;
	}
}
