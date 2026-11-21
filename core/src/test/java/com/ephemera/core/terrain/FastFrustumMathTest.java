package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

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
	}
}
