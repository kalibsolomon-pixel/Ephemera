package com.ephemera.render.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ReusableLongSetCopyTest {
	@Test
	void copyIntoPreservesAllKeysIncludingZeroAcrossTableSizes() {
		assertTrue(ReusableLongSetCopy.isAvailable(), "VarHandle fast-copy should be available on fastutil LongOpenHashSet");
		LongOpenHashSet src = new LongOpenHashSet(16);
		LongOpenHashSet dst = new LongOpenHashSet(256);

		// Populate with 0L and thousands of keys so table resizes
		src.add(0L);
		for (long i = -5000; i <= 5000; i++) {
			src.add(i * 31L);
		}
		assertTrue(ReusableLongSetCopy.copyInto(src, dst));
		assertEquals(src.size(), dst.size());
		assertTrue(dst.contains(0L));
		for (long i = -5000; i <= 5000; i++) {
			assertTrue(dst.contains(i * 31L), "Missing key " + (i * 31L));
		}
		assertFalse(dst.contains(1L));

		// Shrink / remove zero key and copy again cleanly
		src.remove(0L);
		src.remove(31L);
		assertTrue(ReusableLongSetCopy.copyInto(src, dst));
		assertEquals(src.size(), dst.size());
		assertFalse(dst.contains(0L));
		assertFalse(dst.contains(31L));
		assertTrue(dst.contains(62L));
	}
}
