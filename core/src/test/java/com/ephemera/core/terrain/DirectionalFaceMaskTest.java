package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import static com.ephemera.core.terrain.DirectionalFaceMask.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalFaceMaskTest {
	@Test
	void bucketsFollowDirectionOrdinals() {
		// net.minecraft.core.Direction: DOWN, UP, NORTH, SOUTH, WEST, EAST
		assertEquals(0, DOWN);
		assertEquals(1, UP);
		assertEquals(2, NORTH);
		assertEquals(3, SOUTH);
		assertEquals(4, WEST);
		assertEquals(5, EAST);
		assertEquals(6, UNASSIGNED);
		for (int b = 0; b < FACING_COUNT; b++) {
			assertEquals(b, bucketOfDirection(b));
		}
		assertTrue(isPositive(UP) && isPositive(SOUTH) && isPositive(EAST));
		assertFalse(isPositive(DOWN) || isPositive(NORTH) || isPositive(WEST) || isPositive(UNASSIGNED));
		assertEquals(0, axisOf(WEST));
		assertEquals(1, axisOf(UP));
		assertEquals(2, axisOf(SOUTH));
	}

	@Test
	void cameraInsideSectionSeesEveryBucket() {
		assertEquals(ALL, sectionVisibleMask(0, 0, 0, 8, 8, 8));
		assertEquals(ALL, sectionVisibleMask(-16, 64, 32, -8.5, 70, 40));
	}

	@Test
	void cameraWestOfSectionHidesEastFaces() {
		// +X faces lie at x >= ox - 1; a camera at x < ox - 1 is behind every one of them.
		int mask = sectionVisibleMask(0, 0, 0, -1.5, 8, 8);
		assertFalse(contains(mask, EAST));
		assertTrue(contains(mask, WEST));
		// Within the one-block overhang margin both stay visible.
		mask = sectionVisibleMask(0, 0, 0, -0.5, 8, 8);
		assertTrue(contains(mask, EAST));
		assertTrue(contains(mask, WEST));
	}

	@Test
	void cameraEastOfSectionHidesWestFaces() {
		// -X faces lie at x <= ox + 17.
		int mask = sectionVisibleMask(0, 0, 0, 17.5, 8, 8);
		assertFalse(contains(mask, WEST));
		assertTrue(contains(mask, EAST));
		assertTrue(contains(sectionVisibleMask(0, 0, 0, 16.5, 8, 8), WEST));
	}

	@Test
	void cameraBelowAndAboveSection() {
		int below = sectionVisibleMask(0, 64, 0, 8, 62.9, 8);
		assertFalse(contains(below, UP));
		assertTrue(contains(below, DOWN));
		int above = sectionVisibleMask(0, 64, 0, 8, 81.1, 8);
		assertFalse(contains(above, DOWN));
		assertTrue(contains(above, UP));
	}

	@Test
	void cameraNorthAndSouthOfSection() {
		int north = sectionVisibleMask(0, 0, -32, 8, 8, -33.5);
		assertFalse(contains(north, SOUTH));
		assertTrue(contains(north, NORTH));
		int south = sectionVisibleMask(0, 0, -32, 8, 8, -14.5);
		assertFalse(contains(south, NORTH));
		assertTrue(contains(south, SOUTH));
	}

	@Test
	void cornerCameraSeesThreeFacingsPlusUnassigned() {
		int mask = visibleBuckets(-10, -10, -10, 0, 0, 0, 16, 16, 16, 0);
		assertEquals((1 << WEST) | (1 << DOWN) | (1 << NORTH) | (1 << UNASSIGNED), mask);
		mask = sectionVisibleMask(0, 0, 0, 30, 30, 30);
		assertEquals((1 << EAST) | (1 << UP) | (1 << SOUTH) | (1 << UNASSIGNED), mask);
	}

	@Test
	void unassignedIsAlwaysVisible() {
		for (double c : new double[] {-1e6, -20, 0, 8, 20, 1e6}) {
			assertTrue(contains(sectionVisibleMask(0, 0, 0, c, c, c), UNASSIGNED));
		}
	}

	@Test
	void measuredPlanesTightenTheMask() {
		float inf = Float.POSITIVE_INFINITY;
		// Ground top faces at y=5, nothing else: DOWN/NORTH/... empty.
		float[] planes = {-inf, 5f, -inf, inf, -inf, inf};
		assertTrue(contains(visibleMask(8, 5.2, 8, planes, 0), UP));
		assertFalse(contains(visibleMask(8, 4.9, 8, planes, 0), UP));
		assertTrue(contains(visibleMask(8, 4.9, 8, planes, 0.5), UP));
		// Empty buckets never test visible.
		int mask = visibleMask(8, 8, 8, planes, 1);
		assertEquals((1 << UP) | (1 << UNASSIGNED), mask);
		// Negative-facing bucket uses its highest plane.
		float[] west = {-inf, inf, -inf, inf, 3f, inf};
		assertTrue(contains(visibleMask(2.9, 0, 0, west, 0), WEST));
		assertFalse(contains(visibleMask(3.1, 0, 0, west, 0), WEST));
	}

	@Test
	void classifiesNormals() {
		assertEquals(EAST, bucketOf(1, 0, 0));
		assertEquals(WEST, bucketOf(-2, 0, 0));
		assertEquals(DOWN, bucketOf(0, -0.5f, 0));
		assertEquals(SOUTH, bucketOf(0, 0, 3));
		assertEquals(NORTH, bucketOf(0, 0, -3));
		assertEquals(UNASSIGNED, bucketOf(0.7f, 0.7f, 0));
		assertEquals(UNASSIGNED, bucketOf(0, 0, 0));
		assertEquals(UNASSIGNED, bucketOfDirection(-1));
		assertEquals(UNASSIGNED, bucketOfDirection(6));
	}
}
