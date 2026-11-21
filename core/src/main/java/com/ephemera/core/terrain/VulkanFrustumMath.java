package com.ephemera.core.terrain;

/**
 * Pure-Java Gribb-Hartmann frustum plane extraction for Vulkan (Z in [0, 1]) and OpenGL
 * (Z in [-1, 1]) clip matrices, plus AABB intersection tests matching the GPU compute cull shader.
 *
 * <p>Planes are stored as 6 consecutive {@code (nx, ny, nz, d)} tuples (24 floats) with inward-
 * pointing unit normals so a point {@code p} is inside the frustum iff
 * {@code nx*p.x + ny*p.y + nz*p.z + d >= 0} for all 6 planes.
 */
public final class VulkanFrustumMath {
	public static final int PLANE_FLOATS = 24;

	private VulkanFrustumMath() {}

	/**
	 * Extracts 6 normalized inward-facing frustum planes from a column-major 4x4 clip matrix
	 * ({@code ProjMat * ModelViewMat}).
	 *
	 * @param m column-major 16-float matrix ({@code m[col * 4 + row]})
	 * @param zZeroToOne true for Vulkan/D3D clip depth {@code 0 <= z <= w}, false for OpenGL {@code -w <= z <= w}
	 * @param out length &ge; 24 destination array
	 */
	public static void extractPlanes(float[] m, boolean zZeroToOne, float[] out) {
		float r0x = m[0], r0y = m[4], r0z = m[8],  r0w = m[12];
		float r1x = m[1], r1y = m[5], r1z = m[9],  r1w = m[13];
		float r2x = m[2], r2y = m[6], r2z = m[10], r2w = m[14];
		float r3x = m[3], r3y = m[7], r3z = m[11], r3w = m[15];

		// Left:   w + x >= 0
		writeNormalized(out, 0,  r3x + r0x, r3y + r0y, r3z + r0z, r3w + r0w);
		// Right:  w - x >= 0
		writeNormalized(out, 4,  r3x - r0x, r3y - r0y, r3z - r0z, r3w - r0w);
		// Bottom: w + y >= 0
		writeNormalized(out, 8,  r3x + r1x, r3y + r1y, r3z + r1z, r3w + r1w);
		// Top:    w - y >= 0
		writeNormalized(out, 12, r3x - r1x, r3y - r1y, r3z - r1z, r3w - r1w);
		// Near:   z >= 0 (Vulkan) or w + z >= 0 (OpenGL)
		if (zZeroToOne) {
			writeNormalized(out, 16, r2x, r2y, r2z, r2w);
		} else {
			writeNormalized(out, 16, r3x + r2x, r3y + r2y, r3z + r2z, r3w + r2w);
		}
		// Far:    w - z >= 0
		writeNormalized(out, 20, r3x - r2x, r3y - r2y, r3z - r2z, r3w - r2w);
	}

	/**
	 * Conservative AABB-vs-frustum test (p-vertex test) matching {@code cull_meshlets.comp}.
	 * Returns true if the box intersects or is inside the frustum.
	 */
	public static boolean boxIntersects(float[] planes, float minX, float minY, float minZ,
										float maxX, float maxY, float maxZ) {
		for (int i = 0; i < PLANE_FLOATS; i += 4) {
			float nx = planes[i];
			float ny = planes[i + 1];
			float nz = planes[i + 2];
			float d = planes[i + 3];
			float px = nx >= 0f ? maxX : minX;
			float py = ny >= 0f ? maxY : minY;
			float pz = nz >= 0f ? maxZ : minZ;
			if (nx * px + ny * py + nz * pz + d < 0f) {
				return false;
			}
		}
		return true;
	}

	private static void writeNormalized(float[] out, int off, float nx, float ny, float nz, float d) {
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 1.0e-7f) {
			float inv = 1.0f / len;
			out[off] = nx * inv;
			out[off + 1] = ny * inv;
			out[off + 2] = nz * inv;
			out[off + 3] = d * inv;
		} else {
			// Degenerate plane: pass all boxes safely.
			out[off] = 0f;
			out[off + 1] = 0f;
			out[off + 2] = 0f;
			out[off + 3] = 1f;
		}
	}
}
