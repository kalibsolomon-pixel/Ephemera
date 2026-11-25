package com.ephemera.core.lighting;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class ShardedCowLongObjectMapTest {
	@Test
	void basicPutGetRemoveAndZeroKey() {
		ShardedCowLongObjectMap<String> map = new ShardedCowLongObjectMap<>();
		assertTrue(map.isEmpty());
		assertNull(map.get(0L));
		assertFalse(map.containsKey(0L));

		assertNull(map.put(0L, "zero"));
		assertEquals("zero", map.get(0L));
		assertTrue(map.containsKey(0L));
		assertEquals(1, map.size());

		assertEquals("zero", map.put(0L, "zero2"));
		assertEquals("zero2", map.get(0L));
		assertEquals(1, map.size());

		assertNull(map.put(42L, "a"));
		assertNull(map.put(-99L, "b"));
		assertEquals("a", map.get(42L));
		assertEquals("b", map.get(-99L));
		assertEquals(3, map.size());

		assertEquals("zero2", map.remove(0L));
		assertNull(map.get(0L));
		assertFalse(map.containsKey(0L));
		assertEquals(2, map.size());

		assertNull(map.remove(9999L));
		assertEquals("a", map.remove(42L));
		assertEquals("b", map.remove(-99L));
		assertTrue(map.isEmpty());
	}

	@Test
	void snapshotIsImmutableAcrossSubsequentWriterMutations() {
		ShardedCowLongObjectMap<String> writer = new ShardedCowLongObjectMap<>();
		for (long i = -500; i < 500; i++) {
			writer.put(i * 17L, "v" + i);
		}
		ShardedCowLongObjectMap<String> snap1 = writer.snapshot();

		// Mutate, overwrite, insert, and delete on writer after snapshot
		for (long i = -200; i < 200; i++) {
			writer.put(i * 17L, "updated" + i);
		}
		for (long i = 200; i < 400; i++) {
			writer.remove(i * 17L);
		}
		writer.put(0L, "mutatedZero");
		writer.put(999_999L, "brandNew");

		// snap1 must retain 100% of original state
		assertEquals(1000, snap1.size());
		assertNull(snap1.get(999_999L));
		for (long i = -500; i < 500; i++) {
			assertEquals("v" + i, snap1.get(i * 17L), "snap1 corrupted at " + i);
		}

		// snap2 sees the post-mutation state independently from snap1 and later mutations
		ShardedCowLongObjectMap<String> snap2 = writer.snapshot();
		writer.remove(999_999L);
		assertEquals("brandNew", snap2.get(999_999L));
		assertEquals("mutatedZero", snap2.get(0L));
		assertNull(snap2.get(250L * 17L));
		assertEquals("v250", snap1.get(250L * 17L));
	}

	@Test
	void randomizedOracleEquivalenceWithRepeatedSnapshots() {
		Random rng = new Random(0xC0FFEE1234L);
		ShardedCowLongObjectMap<Integer> map = new ShardedCowLongObjectMap<>();
		Map<Long, Integer> oracle = new HashMap<>();

		for (int step = 0; step < 20_000; step++) {
			long key = rng.nextInt(2500) - 1250L;
			int op = rng.nextInt(10);
			if (op < 5) {
				int val = rng.nextInt();
				assertEquals(oracle.put(key, val), map.put(key, val));
			} else if (op < 8) {
				assertEquals(oracle.remove(key), map.remove(key));
			} else {
				assertEquals(oracle.get(key), map.get(key));
				assertEquals(oracle.containsKey(key), map.containsKey(key));
			}
			if (step % 500 == 0) {
				ShardedCowLongObjectMap<Integer> snap = map.snapshot();
				assertEquals(oracle.size(), snap.size());
				for (Map.Entry<Long, Integer> e : oracle.entrySet()) {
					assertEquals(e.getValue(), snap.get(e.getKey()));
				}
			}
		}
		assertEquals(oracle.size(), map.size());
		for (Map.Entry<Long, Integer> e : oracle.entrySet()) {
			assertEquals(e.getValue(), map.get(e.getKey()));
		}
	}

	@Test
	void repeatedSnapshotsWithoutWritesReuseCachedSnapshotInstance() {
		ShardedCowLongObjectMap<String> map = new ShardedCowLongObjectMap<>();
		map.put(10L, "ten");
		map.put(20L, "twenty");

		ShardedCowLongObjectMap<String> snap1 = map.snapshot();
		ShardedCowLongObjectMap<String> snap2 = map.snapshot();
		assertSame(snap1, snap2, "Consecutive snapshots with no intervening mutation should reuse cached snapshot");

		// No-op remove must not invalidate the cached snapshot
		assertNull(map.remove(999L));
		assertSame(snap1, map.snapshot());

		// Real mutation invalidates cache and leaves snap1 untouched
		map.put(30L, "thirty");
		ShardedCowLongObjectMap<String> snap3 = map.snapshot();
		assertNotSame(snap1, snap3);
		assertNull(snap1.get(30L));
		assertEquals("thirty", snap3.get(30L));
		assertSame(snap3, map.snapshot());
	}
}
