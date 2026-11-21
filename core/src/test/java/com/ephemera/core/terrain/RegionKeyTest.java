package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionKeyTest {
	@Test
	void roundTripsSignedRegionCoordinates() {
		int[] xs = {0, 1, -1, 7, -8, 1_000_000, -1_000_000, (1 << 23) - 1, -(1 << 23)};
		int[] ys = {0, 1, -1, 5, -5, 32767, -32768};
		for (int x : xs) {
			for (int y : ys) {
				for (int z : xs) {
					long key = RegionKey.pack(x, y, z);
					assertEquals(x, RegionKey.regionX(key));
					assertEquals(y, RegionKey.regionY(key));
					assertEquals(z, RegionKey.regionZ(key));
				}
			}
		}
	}

	@Test
	void sectionsMapToExpectedRegions() {
		long key = RegionKey.fromSection(-1, -1, -1);
		assertEquals(-1, RegionKey.regionX(key));
		assertEquals(-1, RegionKey.regionY(key));
		assertEquals(-1, RegionKey.regionZ(key));
		assertEquals(-8, RegionKey.originSectionX(key));
		assertEquals(-4, RegionKey.originSectionY(key));
		assertEquals(-8, RegionKey.originSectionZ(key));
		assertEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(7, 3, 7));
		assertNotEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(8, 0, 0));
		assertNotEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(0, 4, 0));
	}

	@Test
	void localIndicesAreDenseAndUnique() {
		Set<Integer> seen = new HashSet<>();
		for (int y = -4; y < 0; y++) {
			for (int z = 16; z < 24; z++) {
				for (int x = -8; x < 0; x++) {
					int idx = RegionKey.localIndex(x, y, z);
					assertTrue(idx >= 0 && idx < RegionKey.SECTIONS_PER_REGION);
					assertTrue(seen.add(idx));
				}
			}
		}
		assertEquals(RegionKey.SECTIONS_PER_REGION, seen.size());
	}
}
