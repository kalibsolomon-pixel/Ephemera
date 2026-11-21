package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import static com.ephemera.core.terrain.DirectionalFaceMask.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalFaceMaskTest {
	@Test
	void cameraInsideSeesEveryBucket() {
		assertEquals(ALL, visibleBuckets(8, 8, 8, 0, 0, 0, 16, 16, 16, 0));
	}

	@Test
	void cameraOnPositiveXSideHidesNegativeXFaces() {
		int mask = visibleBuckets(40, 8, 8, 0, 0, 0, 16, 16, 16, 0);
		assertTrue(contains(mask, POS_X));
		assertFalse(contains(mask, NEG_X));
		assertTrue(contains(mask, POS_Y) && contains(mask, NEG_Y));
		assertTrue(contains(mask, POS_Z) && contains(mask, NEG_Z));
		assertTrue(contains(mask, UNASSIGNED));
	}

	@Test
	void cornerCameraSeesThreeAxisBucketsPlusUnassigned() {
		int mask = visibleBuckets(-10, -10, -10, 0, 0, 0, 16, 16, 16, 0);
		assertEquals((1 << NEG_X) | (1 << NEG_Y) | (1 << NEG_Z) | (1 << UNASSIGNED), mask);
	}

	@Test
	void marginKeepsBorderlineBucketsVisible() {
		// Exactly on the max X plane: +X faces at x=16 are edge-on; margin keeps -X too.
		assertFalse(contains(visibleBuckets(16, 8, 8, 0, 0, 0, 16, 16, 16, 0), NEG_X));
		assertTrue(contains(visibleBuckets(16, 8, 8, 0, 0, 0, 16, 16, 16, 1), NEG_X));
	}

	@Test
	void classifiesNormals() {
		assertEquals(POS_X, bucketOf(1, 0, 0));
		assertEquals(NEG_Y, bucketOf(0, -0.5f, 0));
		assertEquals(POS_Z, bucketOf(0, 0, 3));
		assertEquals(UNASSIGNED, bucketOf(0.7f, 0.7f, 0));
		assertEquals(UNASSIGNED, bucketOf(0, 0, 0));
		assertEquals(NEG_Y, bucketOfDirection(0));
		assertEquals(POS_X, bucketOfDirection(5));
		assertEquals(UNASSIGNED, bucketOfDirection(-1));
	}
}
