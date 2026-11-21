package com.ephemera.core.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class IndexedRectListTest {
	/** Members are {@code {left, top, right, bottom}}; null members have no bounds (like a null ScreenRectangle). */
	private static final IndexedRectList.BoundsSink<int[]> SINK = (member, index) -> {
		if (member != null) {
			index.add(member[0], member[1], member[2], member[3]);
		}
	};

	private static IndexedRectList<int[]> newList() {
		return new IndexedRectList<>(SINK);
	}

	private static int[] rect(int x, int y, int width, int height) {
		return new int[] {x, y, x + width, y + height};
	}

	private static boolean naive(List<int[]> members, int[] q) {
		for (int[] m : members) {
			if (m != null && m[0] < q[2] && m[2] > q[0] && m[1] < q[3] && m[3] > q[1]) {
				return true;
			}
		}
		return false;
	}

	private static void assertParity(IndexedRectList<int[]> list, int[] q) {
		assertEquals(naive(list, q), list.intersectsAny(q[0], q[1], q[2], q[3]), "query " + java.util.Arrays.toString(q));
	}

	@Test
	void emptyListNeverIntersects() {
		assertFalse(newList().intersectsAny(-1000, -1000, 1000, 1000));
	}

	@Test
	void singleMemberRejectsAndAcceptsExactly() {
		IndexedRectList<int[]> list = newList();
		list.add(rect(10, 10, 10, 10));
		assertTrue(list.intersectsAny(15, 15, 16, 16));
		assertTrue(list.intersectsAny(0, 0, 11, 11));
		assertFalse(list.intersectsAny(20, 10, 30, 20));
		assertFalse(list.intersectsAny(0, 0, 10, 10));
		assertFalse(list.intersectsAny(30, 30, 40, 40));
	}

	@Test
	void nullBoundsMembersAreIgnored() {
		IndexedRectList<int[]> list = newList();
		list.add(null);
		list.add(rect(0, 0, 5, 5));
		list.add(null);
		assertTrue(list.intersectsAny(1, 1, 2, 2));
		assertFalse(list.intersectsAny(10, 10, 20, 20));
	}

	@Test
	void appendsAfterQueriesAreSeen() {
		IndexedRectList<int[]> list = newList();
		list.add(rect(0, 0, 10, 10));
		assertFalse(list.intersectsAny(100, 100, 110, 110));
		list.add(rect(100, 100, 10, 10));
		assertTrue(list.intersectsAny(100, 100, 110, 110));
		assertFalse(list.intersectsAny(50, 50, 60, 60));
	}

	@Test
	void structuralChangesRebuildTheIndex() {
		IndexedRectList<int[]> list = newList();
		list.add(rect(0, 0, 10, 10));
		list.add(rect(100, 100, 10, 10));
		assertTrue(list.intersectsAny(105, 105, 106, 106));

		list.remove(1);
		assertFalse(list.intersectsAny(105, 105, 106, 106));

		list.add(0, rect(200, 200, 10, 10));
		assertTrue(list.intersectsAny(205, 205, 206, 206));
		assertTrue(list.intersectsAny(5, 5, 6, 6));

		list.addAll(List.of(rect(300, 300, 10, 10), rect(400, 400, 10, 10)));
		assertTrue(list.intersectsAny(405, 405, 406, 406));

		list.clear();
		assertFalse(list.intersectsAny(-1000, -1000, 1000, 1000));
		list.add(rect(1, 1, 2, 2));
		assertTrue(list.intersectsAny(2, 2, 3, 3));
	}

	@Test
	void setIsNotMissedEvenThoughItDoesNotBumpModCount() {
		IndexedRectList<int[]> list = newList();
		list.add(rect(0, 0, 10, 10));
		assertTrue(list.intersectsAny(5, 5, 6, 6));
		list.set(0, rect(500, 500, 10, 10));
		assertFalse(list.intersectsAny(5, 5, 6, 6));
		assertTrue(list.intersectsAny(505, 505, 506, 506));
	}

	@Test
	void appendAfterOutOfSyncChangeStillAnswersExactly() {
		IndexedRectList<int[]> list = newList();
		list.add(rect(0, 0, 10, 10));
		list.set(0, rect(50, 50, 10, 10));
		list.add(rect(200, 200, 10, 10)); // arrives while the index is stale
		assertTrue(list.intersectsAny(55, 55, 56, 56));
		assertTrue(list.intersectsAny(205, 205, 206, 206));
		assertFalse(list.intersectsAny(5, 5, 6, 6));
	}

	@Test
	void sortAndShuffleKeepAnswersIdentical() {
		Random random = new Random(7);
		IndexedRectList<int[]> list = newList();
		for (int i = 0; i < 50; i++) {
			list.add(rect(random.nextInt(500), random.nextInt(500), random.nextInt(40), random.nextInt(40)));
		}
		int[][] queries = new int[200][];
		for (int i = 0; i < queries.length; i++) {
			queries[i] = rect(random.nextInt(600) - 50, random.nextInt(600) - 50, random.nextInt(60), random.nextInt(60));
		}
		for (int[] q : queries) {
			assertParity(list, q);
		}
		list.sort(Comparator.comparingInt(m -> m[0]));
		for (int[] q : queries) {
			assertParity(list, q);
		}
		Collections.shuffle(list, random);
		for (int[] q : queries) {
			assertParity(list, q);
		}
	}

	@Test
	void iteratorRemoveAndSubListClearAreTracked() {
		IndexedRectList<int[]> list = newList();
		for (int i = 0; i < 6; i++) {
			list.add(rect(i * 100, 0, 10, 10));
		}
		assertTrue(list.intersectsAny(105, 5, 106, 6));
		var it = list.iterator();
		it.next();
		it.next();
		it.remove(); // removes the member at x=100
		assertFalse(list.intersectsAny(105, 5, 106, 6));
		list.subList(0, 2).clear();
		assertFalse(list.intersectsAny(5, 5, 6, 6));
		assertTrue(list.intersectsAny(305, 5, 306, 6));
	}

	@Test
	void randomizedOperationsMatchLinearScan() {
		Random random = new Random(0xC0FFEEL);
		for (int round = 0; round < 300; round++) {
			IndexedRectList<int[]> list = newList();
			int span = 30 + random.nextInt(1500);
			int operations = random.nextInt(60);
			for (int op = 0; op < operations; op++) {
				switch (random.nextInt(8)) {
					case 0 -> {
						if (!list.isEmpty()) {
							list.remove(random.nextInt(list.size()));
						}
					}
					case 1 -> {
						if (!list.isEmpty()) {
							list.set(random.nextInt(list.size()), random.nextInt(5) == 0 ? null : randomRect(random, span));
						}
					}
					case 2 -> list.add(random.nextInt(list.size() + 1), randomRect(random, span));
					default -> list.add(random.nextInt(10) == 0 ? null : randomRect(random, span));
				}
				if (random.nextInt(3) == 0) {
					assertParity(list, randomRect(random, span));
				}
			}
			for (int i = 0; i < 50; i++) {
				assertParity(list, randomRect(random, span));
			}
		}
	}

	@Test
	void cloneDoesNotShareTheIndex() {
		IndexedRectList<int[]> list = newList();
		list.add(rect(0, 0, 10, 10));
		@SuppressWarnings("unchecked")
		ArrayList<int[]> copy = (ArrayList<int[]>) list.clone();
		copy.add(rect(500, 500, 10, 10));
		assertFalse(list.intersectsAny(505, 505, 506, 506));
		assertEquals(1, list.size());
		assertEquals(2, copy.size());
	}

	private static int[] randomRect(Random random, int span) {
		return rect(random.nextInt(span) - span / 4, random.nextInt(span) - span / 4,
			random.nextInt(span / 4 + 1), random.nextInt(span / 4 + 1));
	}
}
