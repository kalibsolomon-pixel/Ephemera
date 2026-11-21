package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BitmaskFaceCullerTest {
	private static final int[][] OFFSETS = {
		{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
	};

	@Test
	void emptyMaskBuriesNothing() {
		BitmaskFaceCuller c = new BitmaskFaceCuller();
		assertTrue(c.isEmpty());
		assertFalse(c.isBuried(8, 8, 8));
		assertEquals(0, c.occludedFaces(8, 8, 8));
	}

	@Test
	void solidVolumeBuriesInteriorAndUsesBorder() {
		BitmaskFaceCuller c = new BitmaskFaceCuller();
		for (int y = -1; y <= 16; y++) {
			for (int z = -1; z <= 16; z++) {
				for (int x = -1; x <= 16; x++) {
					c.set(x, y, z, BitmaskFaceCuller.ALL_FACES);
				}
			}
		}
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					assertTrue(c.isBuried(x, y, z), x + "," + y + "," + z);
				}
			}
		}
		// Open the border above (0, 15, 0): that block is now exposed on +Y only.
		c.set(0, 16, 0, 0);
		assertFalse(c.isBuried(0, 15, 0));
		assertEquals(BitmaskFaceCuller.ALL_FACES & ~(1 << BitmaskFaceCuller.UP), c.occludedFaces(0, 15, 0));
		assertTrue(c.isBuried(1, 15, 0));
	}

	@Test
	void partialFaceMasksRequireTheOpposingFace() {
		BitmaskFaceCuller c = new BitmaskFaceCuller();
		int x = 5, y = 5, z = 5;
		// Each neighbour only occludes with the face pointing back at the centre cell.
		for (int d = 0; d < 6; d++) {
			int[] o = OFFSETS[d];
			c.set(x + o[0], y + o[1], z + o[2], 1 << (d ^ 1));
		}
		assertTrue(c.isBuried(x, y, z));
		// Flip the east neighbour to occlude only on its east face: centre's +X is exposed.
		c.set(x + 1, y, z, 1 << BitmaskFaceCuller.EAST);
		assertFalse(c.isBuried(x, y, z));
		assertEquals(BitmaskFaceCuller.ALL_FACES & ~(1 << BitmaskFaceCuller.EAST), c.occludedFaces(x, y, z));
	}

	@Test
	void matchesReferenceOnRandomVolumes() {
		Random rnd = new Random(1234);
		BitmaskFaceCuller c = new BitmaskFaceCuller();
		int[][][] masks = new int[18][18][18];
		for (int round = 0; round < 8; round++) {
			c.clear();
			for (int y = -1; y <= 16; y++) {
				for (int z = -1; z <= 16; z++) {
					for (int x = -1; x <= 16; x++) {
						int m = rnd.nextInt(4) == 0 ? rnd.nextInt(64) : (rnd.nextBoolean() ? 63 : 0);
						masks[x + 1][y + 1][z + 1] = m;
						c.set(x, y, z, m);
					}
				}
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int expected = 0;
						for (int d = 0; d < 6; d++) {
							int[] o = OFFSETS[d];
							int n = masks[x + o[0] + 1][y + o[1] + 1][z + o[2] + 1];
							if ((n & (1 << (d ^ 1))) != 0) {
								expected |= 1 << d;
							}
						}
						assertEquals(expected, c.occludedFaces(x, y, z));
						assertEquals(expected == 63, c.isBuried(x, y, z));
					}
				}
			}
		}
	}

	@Test
	void clearResetsState() {
		BitmaskFaceCuller c = new BitmaskFaceCuller();
		c.set(3, 3, 3, 63);
		assertFalse(c.isEmpty());
		c.set(3, 3, 3, 0);
		assertTrue(c.isEmpty());
		c.set(4, 4, 4, 63);
		c.clear();
		assertTrue(c.isEmpty());
		assertEquals(0, c.faceMask(4, 4, 4));
	}
}
