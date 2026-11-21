package com.ephemera.core.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GuiBoundsIndexTest {
	/** Vanilla ScreenRectangle: right = x + width, bottom = y + height; a.intersects(b). */
	private static boolean vanillaIntersects(int[] a, int[] b) {
		return a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
	}

	private static int[] rect(int x, int y, int width, int height) {
		return new int[] {x, y, x + width, y + height};
	}

	private static boolean naive(List<int[]> rects, int[] query) {
		for (int[] r : rects) {
			if (vanillaIntersects(r, query)) {
				return true;
			}
		}
		return false;
	}

	@Test
	void emptyIndexNeverIntersects() {
		GuiBoundsIndex index = new GuiBoundsIndex();
		assertFalse(index.intersectsAny(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	@Test
	void touchingEdgesDoNotIntersect() {
		GuiBoundsIndex index = new GuiBoundsIndex();
		index.add(10, 10, 20, 20);
		assertFalse(index.intersectsAny(20, 10, 30, 20));
		assertFalse(index.intersectsAny(0, 10, 10, 20));
		assertFalse(index.intersectsAny(10, 20, 20, 30));
		assertFalse(index.intersectsAny(10, 0, 20, 10));
		assertTrue(index.intersectsAny(19, 19, 30, 30));
	}

	@Test
	void unionBoxRejectDoesNotHideGapHits() {
		GuiBoundsIndex index = new GuiBoundsIndex();
		index.add(0, 0, 10, 10);
		index.add(100, 100, 110, 110);
		// inside the union box but between the two members
		assertFalse(index.intersectsAny(40, 40, 60, 60));
		assertTrue(index.intersectsAny(5, 5, 6, 6));
		assertTrue(index.intersectsAny(105, 105, 120, 120));
	}

	@Test
	void degenerateAndNegativeSizedRectanglesMatchVanilla() {
		GuiBoundsIndex index = new GuiBoundsIndex();
		List<int[]> reference = new ArrayList<>();
		int[][] members = {rect(5, 5, 0, 0), rect(30, 30, 0, 10), rect(50, 50, -10, -10), rect(0, 0, 4, 4)};
		for (int[] m : members) {
			index.add(m[0], m[1], m[2], m[3]);
			reference.add(m);
		}
		for (int x = -5; x < 70; x += 3) {
			for (int y = -5; y < 70; y += 3) {
				for (int size = 0; size < 12; size += 4) {
					int[] q = rect(x, y, size, size);
					assertEquals(naive(reference, q), index.intersectsAny(q[0], q[1], q[2], q[3]),
						"query " + java.util.Arrays.toString(q));
				}
			}
		}
	}

	@Test
	void randomizedParityWithVanillaIntersects() {
		Random random = new Random(0xE9E4E7A1L);
		int mismatches = 0;
		for (int round = 0; round < 400; round++) {
			GuiBoundsIndex index = new GuiBoundsIndex(2);
			List<int[]> reference = new ArrayList<>();
			int members = random.nextInt(40);
			int span = 20 + random.nextInt(2000);
			for (int i = 0; i < members; i++) {
				int[] r = rect(random.nextInt(span) - span / 4, random.nextInt(span) - span / 4,
					random.nextInt(span / 4 + 1), random.nextInt(span / 4 + 1));
				index.add(r[0], r[1], r[2], r[3]);
				reference.add(r);
				if (random.nextInt(4) == 0) {
					// interleave queries with appends
					int[] q = rect(random.nextInt(span) - span / 4, random.nextInt(span) - span / 4,
						random.nextInt(span / 3 + 1), random.nextInt(span / 3 + 1));
					if (naive(reference, q) != index.intersectsAny(q[0], q[1], q[2], q[3])) {
						mismatches++;
					}
				}
			}
			for (int i = 0; i < 100; i++) {
				int[] q = rect(random.nextInt(span * 2) - span, random.nextInt(span * 2) - span,
					random.nextInt(span / 2 + 1), random.nextInt(span / 2 + 1));
				if (naive(reference, q) != index.intersectsAny(q[0], q[1], q[2], q[3])) {
					mismatches++;
				}
			}
			assertEquals(members, index.size());
		}
		assertEquals(0, mismatches);
	}

	@Test
	void extremeCoordinatesDoNotOverflowComparisons() {
		GuiBoundsIndex index = new GuiBoundsIndex();
		index.add(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE + 10, Integer.MIN_VALUE + 10);
		index.add(Integer.MAX_VALUE - 10, Integer.MAX_VALUE - 10, Integer.MAX_VALUE, Integer.MAX_VALUE);
		assertTrue(index.intersectsAny(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0));
		assertTrue(index.intersectsAny(Integer.MAX_VALUE - 5, Integer.MAX_VALUE - 5, Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertFalse(index.intersectsAny(-100, -100, 100, 100));
	}

	@Test
	void clearResetsUnionBox() {
		GuiBoundsIndex index = new GuiBoundsIndex();
		index.add(0, 0, 1000, 1000);
		index.clear();
		assertEquals(0, index.size());
		assertFalse(index.intersectsAny(10, 10, 20, 20));
		index.add(500, 500, 510, 510);
		assertFalse(index.intersectsAny(10, 10, 20, 20));
		assertTrue(index.intersectsAny(505, 505, 506, 506));
	}

	@Test
	void growsPastInitialCapacity() {
		GuiBoundsIndex index = new GuiBoundsIndex(1);
		for (int i = 0; i < 1000; i++) {
			index.add(i * 10, 0, i * 10 + 5, 5);
		}
		assertEquals(1000, index.size());
		assertTrue(index.intersectsAny(9993, 0, 9996, 1));
		assertFalse(index.intersectsAny(5, 0, 10, 5));
	}
}
