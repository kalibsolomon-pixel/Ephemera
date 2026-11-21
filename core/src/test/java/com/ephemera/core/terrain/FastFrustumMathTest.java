package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class FastFrustumMathTest {
	/** Axis-aligned box frustum [-1, 1]^3 expressed as planes. */
	private static FastFrustumMath unitBox() {
		FastFrustumMath f = new FastFrustumMath();
		f.setPlane(0, 1, 0, 0, 1);
		f.setPlane(1, -1, 0, 0, 1);
		f.setPlane(2, 0, 1, 0, 1);
		f.setPlane(3, 0, -1, 0, 1);
		f.setPlane(4, 0, 0, 1, 1);
		f.setPlane(5, 0, 0, -1, 1);
		return f;
	}

	@Test
	void aabbInsideOutsideAndStraddling() {
		FastFrustumMath f = unitBox();
		assertTrue(f.intersectsAabb(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f));
		assertTrue(f.intersectsAabb(0.5f, 0.5f, 0.5f, 3f, 3f, 3f));
		assertFalse(f.intersectsAabb(1.5f, -0.5f, -0.5f, 3f, 0.5f, 0.5f));
		assertFalse(f.intersectsAabb(-0.5f, -0.5f, -9f, 0.5f, 0.5f, -2f));
	}

	@Test
	void identityMatrixYieldsClipCube() {
		float[] identity = {
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			0, 0, 0, 1
		};
		FastFrustumMath f = new FastFrustumMath();
		f.setFromMatrix(identity, false);
		assertTrue(f.intersectsAabb(-0.9f, -0.9f, -0.9f, 0.9f, 0.9f, 0.9f));
		assertFalse(f.intersectsAabb(1.1f, 0, 0, 2, 1, 1));
		assertFalse(f.intersectsAabb(0, 0, -3f, 0.5f, 0.5f, -1.1f));
		f.setFromMatrix(identity, true);
		assertFalse(f.intersectsAabb(0, 0, -0.9f, 0.5f, 0.5f, -0.1f));
		assertTrue(f.intersectsAabb(0, 0, 0.1f, 0.5f, 0.5f, 0.9f));
	}

	@Test
	void distancesToBoxes() {
		assertEquals(0.0, FastFrustumMath.minDistanceSquared(-1, -1, -1, 1, 1, 1));
		assertEquals(25.0, FastFrustumMath.minDistanceSquared(3, 4, -1, 10, 10, 1), 1e-9);
		assertEquals(5.0, FastFrustumMath.minCylindricalDistance(3, -1, 4, 9, 1, 9), 1e-9);
		assertEquals(7.0, FastFrustumMath.minCylindricalDistance(-1, -9, -1, 1, -7, 1), 1e-9);
	}

	@Test
	void fogCullingMatchesShaderSemantics() {
		// Render-distance fog is cylindrical: a box 200 blocks away horizontally is fogged at rdEnd 160.
		assertTrue(FastFrustumMath.isFullyFogged(200, -10, 0, 216, 10, 16, Float.MAX_VALUE, 160));
		// A box touching the camera column is never fogged.
		assertFalse(FastFrustumMath.isFullyFogged(-8, -8, -8, 8, 8, 8, 1, 160));
		// Spherical environmental fog (e.g. blindness) can cull boxes the cylindrical term keeps.
		assertTrue(FastFrustumMath.isFullyFogged(6, 6, 6, 20, 20, 20, 5, 160));
		assertFalse(FastFrustumMath.isFullyFogged(2, 2, 2, 20, 20, 20, 5, 160));
		// No fog configured at all.
		assertFalse(FastFrustumMath.isFullyFogged(1e6, 0, 0, 1e6 + 16, 16, 16, Float.MAX_VALUE, Float.MAX_VALUE));
		// Non-positive fog ends conservatively mark boxes as fogged.
		assertTrue(FastFrustumMath.isFullyFogged(0, 0, 0, 1, 1, 1, 0.0f, Float.MAX_VALUE));
		assertTrue(FastFrustumMath.isFullyFogged(0, 0, 0, 1, 1, 1, Float.MAX_VALUE, -4.0f));
	}

	@Test
	void intersectsCubeMatchesAabbAcrossRandomPlanes() {
		SplittableRandom rng = new SplittableRandom(0xE0E4E8AL);
		FastFrustumMath frustum = new FastFrustumMath();
		int insideCount = 0;
		int outsideCount = 0;
		for (int trial = 0; trial < 200; trial++) {
			for (int p = 0; p < 6; p++) {
				float a = (float) (rng.nextDouble() * 2.0 - 1.0);
				float b = (float) (rng.nextDouble() * 2.0 - 1.0);
				float c = (float) (rng.nextDouble() * 2.0 - 1.0);
				float len = (float) Math.sqrt(a * a + b * b + c * c);
				if (len < 1e-4f) {
					a = 1f; b = 0f; c = 0f; len = 1f;
				}
				float d = (float) (rng.nextDouble() * 40.0 + 5.0);
				frustum.setPlane(p, a / len, b / len, c / len, d);
			}
			for (int sample = 0; sample < 50; sample++) {
				float cx = (float) (rng.nextDouble() * 120.0 - 60.0);
				float cy = (float) (rng.nextDouble() * 120.0 - 60.0);
				float cz = (float) (rng.nextDouble() * 120.0 - 60.0);
				float r = (float) (rng.nextDouble() * 8.0);
				// Skip hairline floating-point boundary cases within 1e-3 of any plane.
				boolean cube = frustum.intersectsCube(cx, cy, cz, r);
				boolean aabbMin = frustum.intersectsAabb(cx - r + 1e-3f, cy - r + 1e-3f, cz - r + 1e-3f,
					cx + r - 1e-3f, cy + r - 1e-3f, cz + r - 1e-3f);
				boolean aabbMax = frustum.intersectsAabb(cx - r - 1e-3f, cy - r - 1e-3f, cz - r - 1e-3f,
					cx + r + 1e-3f, cy + r + 1e-3f, cz + r + 1e-3f);
				if (aabbMin == aabbMax) {
					assertEquals(aabbMin, cube, "Mismatch at trial=" + trial + " sample=" + sample);
					if (cube) {
						insideCount++;
					} else {
						outsideCount++;
					}
				}
			}
		}
		assertTrue(insideCount > 500 && outsideCount > 500,
			"Expected balanced inside/outside coverage: in=" + insideCount + ", out=" + outsideCount);
	}
}
