package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RegionSlabAllocatorTest {
	@Test
	void roundsToPowerOfTwoSlabClasses() {
		RegionSlabAllocator a = new RegionSlabAllocator(1 << 20, 4096);
		assertEquals(0, a.sizeClass(1));
		assertEquals(0, a.sizeClass(4096));
		assertEquals(1, a.sizeClass(4097));
		assertEquals(2, a.sizeClass(3 * 4096));
		assertEquals(8 * 4096, a.reservedBytes(5 * 4096));
		assertEquals(-1, a.sizeClass(2L << 20));
	}

	@Test
	void freedBlocksAreReusedLifo() {
		RegionSlabAllocator a = new RegionSlabAllocator(1 << 20, 1024);
		long x = a.allocate(1000);
		long y = a.allocate(1000);
		assertNotEquals(x, y);
		a.free(x);
		assertEquals(x, a.allocate(500));
		a.free(y);
		a.free(x);
		assertEquals(x, a.allocate(1));
		assertEquals(y, a.allocate(1));
	}

	@Test
	void neverOverlapsAndTracksUsage() {
		Random rnd = new Random(99);
		RegionSlabAllocator a = new RegionSlabAllocator(8L << 20, 256);
		List<long[]> live = new ArrayList<>();
		for (int i = 0; i < 20_000; i++) {
			if (!live.isEmpty() && rnd.nextInt(3) == 0) {
				long[] blk = live.remove(rnd.nextInt(live.size()));
				a.free(blk[0]);
			} else {
				long size = 1 + rnd.nextInt(16 * 1024);
				long off = a.allocate(size);
				if (off == RegionSlabAllocator.FAILED) {
					continue;
				}
				long reserved = a.blockSize(off);
				assertTrue(reserved >= size);
				assertTrue(off + reserved <= a.capacityBytes());
				live.add(new long[] {off, reserved});
			}
		}
		long used = 0;
		live.sort((p, q) -> Long.compare(p[0], q[0]));
		for (int i = 0; i < live.size(); i++) {
			used += live.get(i)[1];
			if (i > 0) {
				long[] prev = live.get(i - 1);
				assertTrue(prev[0] + prev[1] <= live.get(i)[0], "blocks overlap");
			}
		}
		assertEquals(used, a.usedBytes());
		assertEquals(live.size(), a.liveAllocations());
		assertTrue(a.highWaterBytes() <= a.capacityBytes());
	}

	@Test
	void reportsExhaustionAndRejectsDoubleFree() {
		RegionSlabAllocator a = new RegionSlabAllocator(4096, 1024);
		long b0 = a.allocate(2048);
		long b1 = a.allocate(2048);
		assertNotEquals(RegionSlabAllocator.FAILED, b0);
		assertNotEquals(RegionSlabAllocator.FAILED, b1);
		assertEquals(RegionSlabAllocator.FAILED, a.allocate(1));
		a.free(b0);
		assertThrows(IllegalStateException.class, () -> a.free(b0));
		assertThrows(IllegalArgumentException.class, () -> a.free(17));
		a.reset();
		assertEquals(0, a.usedBytes());
		assertEquals(0, a.allocate(4096));
	}
}
