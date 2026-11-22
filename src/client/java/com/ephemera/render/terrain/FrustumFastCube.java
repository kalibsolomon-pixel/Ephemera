package com.ephemera.render.terrain;

/**
 * Fast 1-pass sphere-cube frustum intersection exposed by {@code FrustumFastMixin}.
 */
public interface FrustumFastCube {
	/**
	 * True when the axis-aligned cube {@code [cx - radius, cx + radius]^3} intersects or lies
	 * inside all 6 frustum planes. Algebraically identical to testing the p-vertex of the cube
	 * against all 6 Gribb-Hartmann planes.
	 */
	boolean ephemera$intersectsCube(double cx, double cy, double cz, double radius);

	/**
	 * Zero-allocation 6-plane p-vertex frustum visibility check for an axis-aligned bounding box
	 * specified by primitive coordinates.
	 */
	boolean ephemera$isVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}
