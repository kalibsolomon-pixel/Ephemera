package com.ephemera.core.terrain;

/**
 * Seven-bucket directional face culling. Opaque section geometry is grouped by facing, in
 * {@code Direction} ordinal order ({@code DOWN, UP, NORTH, SOUTH, WEST, EAST}), plus
 * {@code UNASSIGNED} for everything that is not a single-sided axis-aligned quad. A bucket
 * can only contain front-facing quads when the camera is on the outward side of at least
 * one plane that a quad in that bucket lies in; otherwise back-face culling would discard
 * every quad in it anyway and the bucket need not be drawn.
 */
public final class DirectionalFaceMask {
	/** -Y */
	public static final int DOWN = 0;
	/** +Y */
	public static final int UP = 1;
	/** -Z */
	public static final int NORTH = 2;
	/** +Z */
	public static final int SOUTH = 3;
	/** -X */
	public static final int WEST = 4;
	/** +X */
	public static final int EAST = 5;
	public static final int UNASSIGNED = 6;
	public static final int FACING_COUNT = 6;
	public static final int BUCKET_COUNT = 7;
	public static final int ALL = (1 << BUCKET_COUNT) - 1;

	/** Block models may extend one block outside their cell. */
	public static final double MODEL_MARGIN = 1.0;
	/** A 16-block section. */
	public static final double SECTION_SIZE = 16.0;

	private DirectionalFaceMask() {}

	/**
	 * Bitmask of buckets that may contain front-facing quads for a camera at
	 * {@code (camX, camY, camZ)} viewing geometry confined to the box
	 * {@code [min, min + size]} expanded by {@code margin} on every side.
	 */
	public static int visibleBuckets(double camX, double camY, double camZ,
									 double minX, double minY, double minZ,
									 double sizeX, double sizeY, double sizeZ,
									 double margin) {
		double loX = minX - margin;
		double loY = minY - margin;
		double loZ = minZ - margin;
		double hiX = minX + sizeX + margin;
		double hiY = minY + sizeY + margin;
		double hiZ = minZ + sizeZ + margin;
		int mask = 1 << UNASSIGNED;
		// A +X face at plane p faces the camera iff camX > p; the lowest possible p is loX.
		mask |= (camX > loX ? 1 : 0) << EAST;
		mask |= (camX < hiX ? 1 : 0) << WEST;
		mask |= (camY > loY ? 1 : 0) << UP;
		mask |= (camY < hiY ? 1 : 0) << DOWN;
		mask |= (camZ > loZ ? 1 : 0) << SOUTH;
		mask |= (camZ < hiZ ? 1 : 0) << NORTH;
		return mask;
	}

	/**
	 * Conservative mask for a whole 16³ section at block origin {@code (ox, oy, oz)}, with
	 * the {@link #MODEL_MARGIN} overhang allowance.
	 */
	public static int sectionVisibleMask(int ox, int oy, int oz, double camX, double camY, double camZ) {
		return visibleBuckets(camX, camY, camZ, ox, oy, oz, SECTION_SIZE, SECTION_SIZE, SECTION_SIZE, MODEL_MARGIN);
	}

	/**
	 * Mask from measured plane extremes (see {@link QuadBuckets#planes()}): {@code planes[b]}
	 * is the lowest plane coordinate of a positive-facing bucket and the highest of a
	 * negative-facing one, in the same frame as the camera coordinates. Empty buckets hold
	 * an infinity that never tests visible.
	 */
	public static int visibleMask(double camX, double camY, double camZ, float[] planes, double margin) {
		int mask = 1 << UNASSIGNED;
		mask |= (camY < planes[DOWN] + margin ? 1 : 0) << DOWN;
		mask |= (camY > planes[UP] - margin ? 1 : 0) << UP;
		mask |= (camZ < planes[NORTH] + margin ? 1 : 0) << NORTH;
		mask |= (camZ > planes[SOUTH] - margin ? 1 : 0) << SOUTH;
		mask |= (camX < planes[WEST] + margin ? 1 : 0) << WEST;
		mask |= (camX > planes[EAST] - margin ? 1 : 0) << EAST;
		return mask;
	}

	/** Bucket of a quad with the given (not necessarily normalized) face normal. */
	public static int bucketOf(float nx, float ny, float nz) {
		float ax = Math.abs(nx);
		float ay = Math.abs(ny);
		float az = Math.abs(nz);
		if (ay == 0f && az == 0f && ax != 0f) {
			return nx > 0 ? EAST : WEST;
		}
		if (ax == 0f && az == 0f && ay != 0f) {
			return ny > 0 ? UP : DOWN;
		}
		if (ax == 0f && ay == 0f && az != 0f) {
			return nz > 0 ? SOUTH : NORTH;
		}
		return UNASSIGNED;
	}

	/** Bucket for one of the six voxel directions (0=-Y,1=+Y,2=-Z,3=+Z,4=-X,5=+X), or {@link #UNASSIGNED}. */
	public static int bucketOfDirection(int direction) {
		return direction >= 0 && direction < FACING_COUNT ? direction : UNASSIGNED;
	}

	/** True for the buckets whose outward normal points along +X, +Y or +Z. */
	public static boolean isPositive(int bucket) {
		return bucket < FACING_COUNT && (bucket & 1) == 1;
	}

	/** Axis of a facing bucket: 0 = X, 1 = Y, 2 = Z. */
	public static int axisOf(int bucket) {
		return switch (bucket) {
			case DOWN, UP -> 1;
			case NORTH, SOUTH -> 2;
			case WEST, EAST -> 0;
			default -> -1;
		};
	}

	public static boolean contains(int mask, int bucket) {
		return (mask & (1 << bucket)) != 0;
	}
}
