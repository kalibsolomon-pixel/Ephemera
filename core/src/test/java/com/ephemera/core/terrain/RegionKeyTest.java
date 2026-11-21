package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionKeyTest {
	@Test
	void regionIs16By8By16Sections() {
		assertEquals(16, RegionKey.WIDTH);
		assertEquals(8, RegionKey.HEIGHT);
		assertEquals(2048, RegionKey.SECTIONS_PER_REGION);
		assertEquals(256, RegionKey.BLOCK_WIDTH);
		assertEquals(128, RegionKey.BLOCK_HEIGHT);
	}

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
		assertEquals(-16, RegionKey.originSectionX(key));
		assertEquals(-8, RegionKey.originSectionY(key));
		assertEquals(-16, RegionKey.originSectionZ(key));
		assertEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(15, 7, 15));
		assertNotEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(16, 0, 0));
		assertNotEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(0, 8, 0));
		assertNotEquals(RegionKey.fromSection(0, 0, 0), RegionKey.fromSection(-1, 0, 0));
		assertEquals(RegionKey.fromSection(-16, -8, -16), RegionKey.fromSection(-1, -1, -1));
	}

	@Test
	void blockOriginsFloorTowardNegativeInfinity() {
		int[][] cases = {
			// block, x/z origin, y origin
			{0, 0, 0},
			{15, 0, 0},
			{16, 0, 0},
			{127, 0, 0},
			{128, 0, 128},
			{255, 0, 128},
			{256, 256, 256},
			{-1, -256, -128},
			{-16, -256, -128},
			{-128, -256, -128},
			{-129, -256, -256},
			{-256, -256, -256},
			{-272, -512, -384},
			{-64, -256, -128},
			{320, 256, 256},
		};
		for (int[] c : cases) {
			int block = c[0];
			assertEquals(c[1], RegionKey.originBlockX(block), "x " + block);
			assertEquals(c[1], RegionKey.originBlockZ(block), "z " + block);
			assertEquals(c[2], RegionKey.originBlockY(block), "y " + block);
			assertEquals(Math.floorDiv(block, 256) * 256, RegionKey.originBlockX(block));
			assertEquals(Math.floorDiv(block, 128) * 128, RegionKey.originBlockY(block));
		}
	}

	@Test
	void sectionOffsetsInsideRegionAreBounded() {
		// sectionOrigin - regionOrigin is always a non-negative multiple of 16 inside the region.
		for (int s = -40; s <= 40; s++) {
			int origin = s << 4;
			int dx = origin - RegionKey.originBlockX(origin);
			int dy = origin - RegionKey.originBlockY(origin);
			assertTrue(dx >= 0 && dx <= 240 && (dx & 15) == 0, "dx " + dx + " for " + origin);
			assertTrue(dy >= 0 && dy <= 112 && (dy & 15) == 0, "dy " + dy + " for " + origin);
			long key = RegionKey.fromBlock(origin, origin, origin);
			assertEquals(RegionKey.originBlockX(origin), RegionKey.originSectionX(key) << 4);
			assertEquals(RegionKey.originBlockY(origin), RegionKey.originSectionY(key) << 4);
			assertEquals(RegionKey.originBlockZ(origin), RegionKey.originSectionZ(key) << 4);
		}
		assertEquals(240, -16 - RegionKey.originBlockX(-16));
		assertEquals(0, -256 - RegionKey.originBlockX(-256));
		assertEquals(240, -272 - RegionKey.originBlockX(-272));
		assertEquals(112, -16 - RegionKey.originBlockY(-16));
	}

	@Test
	void localIndicesAreDenseAndUnique() {
		Set<Integer> seen = new HashSet<>();
		for (int y = -8; y < 0; y++) {
			for (int z = 16; z < 32; z++) {
				for (int x = -16; x < 0; x++) {
					int idx = RegionKey.localIndex(x, y, z);
					assertTrue(idx >= 0 && idx < RegionKey.SECTIONS_PER_REGION);
					assertTrue(seen.add(idx));
				}
			}
		}
		assertEquals(RegionKey.SECTIONS_PER_REGION, seen.size());
	}
}
