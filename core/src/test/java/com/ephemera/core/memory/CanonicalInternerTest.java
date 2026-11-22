package com.ephemera.core.memory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalInternerTest {
	@Test
	void deduplicatesStructurallyEqualArraysBeyondOld2048Limit() {
		CanonicalInterner<int[]> interner = new CanonicalInterner<>(64, 16384, Arrays::hashCode, Arrays::equals);
		int[][] originals = new int[5000][];
		for (int i = 0; i < originals.length; i++) {
			originals[i] = new int[] { i, i * 31, i ^ 0x5A5A };
			assertSame(originals[i], interner.intern(originals[i]));
		}
		assertEquals(5000, interner.size());

		// Second pass with fresh copies must hit 100% canonical matches (zero thrashing).
		for (int i = 0; i < originals.length; i++) {
			int[] copy = new int[] { i, i * 31, i ^ 0x5A5A };
			assertSame(originals[i], interner.intern(copy));
		}
		assertEquals(5000L, interner.deduplicatedCount());
		assertEquals(5000, interner.size());
	}

	@Test
	void clearDropsReferencesAndShrinksBackingTable() {
		CanonicalInterner<String> interner = new CanonicalInterner<>(64, 4096, String::hashCode, String::equals);
		for (int i = 0; i < 1000; i++) {
			interner.intern("key-" + i);
		}
		assertTrue(interner.capacity() >= 1024);
		interner.clear();
		assertEquals(0, interner.size());
		assertEquals(64, interner.capacity());
	}

	@Test
	void respectsHardCapacityBoundWithoutGrowingUnboundedly() {
		CanonicalInterner<Integer> interner = new CanonicalInterner<>(64, 128, Integer::intValue, Integer::equals);
		for (int i = 0; i < 5000; i++) {
			assertNotNull(interner.intern(i));
		}
		assertEquals(128, interner.capacity());
		assertTrue(interner.size() <= 84, "load must stay bounded near LOAD_FACTOR so probing stays O(1)");
	}
}
