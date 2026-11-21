package com.ephemera.render.fastwrite;

import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3fc;

import static java.lang.Float.floatToRawIntBits;

/**
 * Precompiled axis-aligned box description of a {@link ModelPart.Cube}, verified bit-for-bit
 * against the cube's polygons at construction time. Enables 3-edge-vector 8-corner derivation
 * and zero-loop native-memory emission in {@link FastCuboidWriter}.
 */
public final class CompiledCuboid {
	// Normal axis codes matching the 6 signed axes.
	static final int POS_X = 0, NEG_X = 1, POS_Y = 2, NEG_Y = 3, POS_Z = 4, NEG_Z = 5;

	final float x0, y0, z0;
	final float dx, dy, dz;
	final int faceCount;
	/** Bitmask of axes used by present faces: bit0=X, bit1=Y, bit2=Z. */
	final int axisMask;
	/** Packed 4 corner indices (4 bits each, 0..7 = bit0:x1, bit1:y1, bit2:z1) per face. */
	final int[] cornerPacks;
	/** Normal code (0..5) per face. */
	final byte[] normalCodes;
	/** Flat UV pairs (8 floats per face: u0,v0, u1,v1, u2,v2, u3,v3). */
	final float[] uvs;
	/** Original polygon references for O(1) mutation detection. */
	private final ModelPart.Polygon[] snapPolygons;

	private CompiledCuboid(float x0, float y0, float z0, float dx, float dy, float dz,
			int faceCount, int axisMask, int[] cornerPacks, byte[] normalCodes, float[] uvs,
			ModelPart.Polygon[] snapPolygons) {
		this.x0 = x0;
		this.y0 = y0;
		this.z0 = z0;
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.faceCount = faceCount;
		this.axisMask = axisMask;
		this.cornerPacks = cornerPacks;
		this.normalCodes = normalCodes;
		this.uvs = uvs;
		this.snapPolygons = snapPolygons;
	}

	/**
	 * Attempts to compile {@code polygons} into a flat axis-aligned box representation.
	 * Returns {@code null} if any polygon is malformed, non-axis-aligned, or uses coordinates
	 * outside the cube's 8 corners (in which case {@link FastCuboidWriter} falls back cleanly).
	 */
	public static CompiledCuboid compile(ModelPart.Polygon[] polygons) {
		if (polygons == null) {
			return null;
		}
		int n = polygons.length;
		if (n == 0 || n > 6) {
			return null;
		}
		float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
		for (int f = 0; f < n; f++) {
			ModelPart.Polygon p = polygons[f];
			if (p == null || p.normal() == null) {
				return null;
			}
			ModelPart.Vertex[] verts = p.vertices();
			if (verts == null || verts.length != 4) {
				return null;
			}
			for (int c = 0; c < 4; c++) {
				ModelPart.Vertex v = verts[c];
				if (v == null) {
					return null;
				}
				float vx = v.worldX(), vy = v.worldY(), vz = v.worldZ();
				if (!Float.isFinite(vx + vy + vz)) {
					return null;
				}
				if (vx < minX) minX = vx;
				if (vx > maxX) maxX = vx;
				if (vy < minY) minY = vy;
				if (vy > maxY) maxY = vy;
				if (vz < minZ) minZ = vz;
				if (vz > maxZ) maxZ = vz;
			}
		}
		float dx = maxX - minX;
		float dy = maxY - minY;
		float dz = maxZ - minZ;
		// Verify exact float addition reconstruction for the upper bounds.
		if (floatToRawIntBits(minX + dx) != floatToRawIntBits(maxX)
				|| floatToRawIntBits(minY + dy) != floatToRawIntBits(maxY)
				|| floatToRawIntBits(minZ + dz) != floatToRawIntBits(maxZ)) {
			return null;
		}
		int x0Bits = floatToRawIntBits(minX), x1Bits = floatToRawIntBits(maxX);
		int y0Bits = floatToRawIntBits(minY), y1Bits = floatToRawIntBits(maxY);
		int z0Bits = floatToRawIntBits(minZ), z1Bits = floatToRawIntBits(maxZ);

		int[] cornerPacks = new int[n];
		byte[] normalCodes = new byte[n];
		float[] uvs = new float[n * 8];
		int axisMask = 0;

		for (int f = 0; f < n; f++) {
			ModelPart.Polygon p = polygons[f];
			int nc = axisNormalCode(p.normal());
			if (nc < 0) {
				return null;
			}
			normalCodes[f] = (byte) nc;
			axisMask |= 1 << (nc >>> 1);

			ModelPart.Vertex[] verts = p.vertices();
			int pack = 0;
			int uvOff = f * 8;
			for (int c = 0; c < 4; c++) {
				ModelPart.Vertex v = verts[c];
				int bx = matchBound(floatToRawIntBits(v.worldX()), x0Bits, x1Bits);
				int by = matchBound(floatToRawIntBits(v.worldY()), y0Bits, y1Bits);
				int bz = matchBound(floatToRawIntBits(v.worldZ()), z0Bits, z1Bits);
				if ((bx | by | bz) < 0) {
					return null;
				}
				int corner = bx | (by << 1) | (bz << 2);
				pack |= corner << (c * 4);
				uvs[uvOff + c * 2] = v.u();
				uvs[uvOff + c * 2 + 1] = v.v();
			}
			cornerPacks[f] = pack;
		}
		return new CompiledCuboid(minX, minY, minZ, dx, dy, dz, n, axisMask, cornerPacks, normalCodes, uvs, polygons.clone());
	}

	boolean matches(ModelPart.Polygon[] polygons) {
		ModelPart.Polygon[] snap = snapPolygons;
		if (polygons.length != snap.length) {
			return false;
		}
		for (int i = 0; i < snap.length; i++) {
			if (polygons[i] != snap[i]) {
				return false;
			}
		}
		return true;
	}

	private static int matchBound(int bits, int b0, int b1) {
		if (bits == b0) return 0;
		if (bits == b1) return 1;
		return -1;
	}

	private static int axisNormalCode(Vector3fc n) {
		float x = n.x(), y = n.y(), z = n.z();
		if (y == 0.0f && z == 0.0f) {
			if (x == 1.0f) return POS_X;
			if (x == -1.0f) return NEG_X;
		} else if (x == 0.0f && z == 0.0f) {
			if (y == 1.0f) return POS_Y;
			if (y == -1.0f) return NEG_Y;
		} else if (x == 0.0f && y == 0.0f) {
			if (z == 1.0f) return POS_Z;
			if (z == -1.0f) return NEG_Z;
		}
		return -1;
	}
}
