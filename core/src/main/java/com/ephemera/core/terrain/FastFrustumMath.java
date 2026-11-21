package com.ephemera.core.terrain;

/**
 * Flat-array six-plane frustum and fog-distance culling math.
 *
 * <p>Planes are stored as {@code (a, b, c, d)} with the inside satisfying
 * {@code a*x + b*y + c*z + d >= 0}. The AABB test picks the box corner furthest along
 * each plane normal with arithmetic selects, so the per-plane work is branch-free; the
 * six results are combined before the single return branch.
 *
 * <p>The fog helpers mirror the vanilla terrain shader: the final fog factor is the
 * maximum of a spherical environmental term and a cylindrical render-distance term, so a
 * box is completely fogged when either distance is past its fog end at every point.
 */
public final class FastFrustumMath {
	private final float[] planes = new float[24];

	/** Sets plane {@code i} (0..5). */
	public void setPlane(int i, float a, float b, float c, float d) {
		int o = i << 2;
		planes[o] = a;
		planes[o + 1] = b;
		planes[o + 2] = c;
		planes[o + 3] = d;
	}

	/**
	 * Extracts the six planes from a combined projection*view matrix given in column-major
	 * order ({@code m[col * 4 + row]}), using the Gribb-Hartmann method for a clip space
	 * whose depth range is [-1, 1] or [0, 1] ({@code zeroToOneDepth}).
	 */
	public void setFromMatrix(float[] m, boolean zeroToOneDepth) {
		float r0x = m[0], r0y = m[4], r0z = m[8], r0w = m[12];
		float r1x = m[1], r1y = m[5], r1z = m[9], r1w = m[13];
		float r2x = m[2], r2y = m[6], r2z = m[10], r2w = m[14];
		float r3x = m[3], r3y = m[7], r3z = m[11], r3w = m[15];
		setNormalized(0, r3x + r0x, r3y + r0y, r3z + r0z, r3w + r0w); // left
		setNormalized(1, r3x - r0x, r3y - r0y, r3z - r0z, r3w - r0w); // right
		setNormalized(2, r3x + r1x, r3y + r1y, r3z + r1z, r3w + r1w); // bottom
		setNormalized(3, r3x - r1x, r3y - r1y, r3z - r1z, r3w - r1w); // top
		if (zeroToOneDepth) {
			setNormalized(4, r2x, r2y, r2z, r2w); // near
		} else {
			setNormalized(4, r3x + r2x, r3y + r2y, r3z + r2z, r3w + r2w); // near
		}
		setNormalized(5, r3x - r2x, r3y - r2y, r3z - r2z, r3w - r2w); // far
	}

	private void setNormalized(int i, float a, float b, float c, float d) {
		float len = (float) Math.sqrt(a * a + b * b + c * c);
		float inv = len > 0f ? 1f / len : 0f;
		setPlane(i, a * inv, b * inv, c * inv, d * inv);
	}

	/** True when the box intersects or lies inside all six planes. */
	public boolean intersectsAabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		float[] p = planes;
		int outside = 0;
		for (int o = 0; o < 24; o += 4) {
			float a = p[o], b = p[o + 1], c = p[o + 2], d = p[o + 3];
			float x = a >= 0f ? maxX : minX;
			float y = b >= 0f ? maxY : minY;
			float z = c >= 0f ? maxZ : minZ;
			outside |= (a * x + b * y + c * z + d < 0f) ? 1 : 0;
		}
		return outside == 0;
	}

	/** Squared distance from the origin to the closest point of a box given relative to the origin. */
	public static double minDistanceSquared(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double dx = axisGap(minX, maxX);
		double dy = axisGap(minY, maxY);
		double dz = axisGap(minZ, maxZ);
		return dx * dx + dy * dy + dz * dz;
	}

	/** Closest cylindrical fog distance (max of horizontal radius and |y|) over a camera-relative box. */
	public static double minCylindricalDistance(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double dx = axisGap(minX, maxX);
		double dz = axisGap(minZ, maxZ);
		double dy = axisGap(minY, maxY);
		return Math.max(Math.sqrt(dx * dx + dz * dz), dy);
	}

	/**
	 * True when every point of the camera-relative box is fully fogged: its spherical
	 * distance is at least {@code environmentalEnd} or its cylindrical distance is at
	 * least {@code renderDistanceEnd}.
	 */
	public static boolean isFullyFogged(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
										float environmentalEnd, float renderDistanceEnd) {
		if (environmentalEnd < Float.MAX_VALUE) {
			double env = environmentalEnd;
			if (minDistanceSquared(minX, minY, minZ, maxX, maxY, maxZ) >= env * env) {
				return true;
			}
		}
		if (renderDistanceEnd < Float.MAX_VALUE) {
			return minCylindricalDistance(minX, minY, minZ, maxX, maxY, maxZ) >= renderDistanceEnd;
		}
		return false;
	}

	/** Distance from 0 to the interval [min, max] along one axis (0 when inside). */
	private static double axisGap(double min, double max) {
		return Math.max(0.0, Math.max(min, -max));
	}
}
