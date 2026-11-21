package com.ephemera.core.terrain;

/**
 * Seven-bucket directional face culling. Geometry in a section is grouped by facing:
 * {@code -X, +X, -Y, +Y, -Z, +Z} for axis-aligned quads, plus {@code UNASSIGNED} for
 * everything else. A bucket can only contain visible (front-facing) quads when the camera
 * is on the positive side of at least one plane that a quad in that bucket might lie in.
 */
public final class DirectionalFaceMask {
	public static final int NEG_X = 0;
	public static final int POS_X = 1;
	public static final int NEG_Y = 2;
	public static final int POS_Y = 3;
	public static final int NEG_Z = 4;
	public static final int POS_Z = 5;
	public static final int UNASSIGNED = 6;
	public static final int BUCKET_COUNT = 7;
	public static final int ALL = (1 << BUCKET_COUNT) - 1;

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
		mask |= (camX > loX ? 1 : 0) << POS_X;
		mask |= (camX < hiX ? 1 : 0) << NEG_X;
		mask |= (camY > loY ? 1 : 0) << POS_Y;
		mask |= (camY < hiY ? 1 : 0) << NEG_Y;
		mask |= (camZ > loZ ? 1 : 0) << POS_Z;
		mask |= (camZ < hiZ ? 1 : 0) << NEG_Z;
		return mask;
	}

	/** Bucket of a quad with the given (not necessarily normalized) face normal. */
	public static int bucketOf(float nx, float ny, float nz) {
		float ax = Math.abs(nx);
		float ay = Math.abs(ny);
		float az = Math.abs(nz);
		if (ay == 0f && az == 0f && ax != 0f) {
			return nx > 0 ? POS_X : NEG_X;
		}
		if (ax == 0f && az == 0f && ay != 0f) {
			return ny > 0 ? POS_Y : NEG_Y;
		}
		if (ax == 0f && ay == 0f && az != 0f) {
			return nz > 0 ? POS_Z : NEG_Z;
		}
		return UNASSIGNED;
	}

	/** Bucket for one of the six voxel directions (0=-Y,1=+Y,2=-Z,3=+Z,4=-X,5=+X), or {@link #UNASSIGNED}. */
	public static int bucketOfDirection(int direction) {
		return switch (direction) {
			case 0 -> NEG_Y;
			case 1 -> POS_Y;
			case 2 -> NEG_Z;
			case 3 -> POS_Z;
			case 4 -> NEG_X;
			case 5 -> POS_X;
			default -> UNASSIGNED;
		};
	}

	public static boolean contains(int mask, int bucket) {
		return (mask & (1 << bucket)) != 0;
	}
}
