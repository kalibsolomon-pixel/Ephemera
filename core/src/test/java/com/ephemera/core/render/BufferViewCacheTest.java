package com.ephemera.core.render;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferViewCacheTest {
	@Test
	void keepsRangesAndHandlesAtNativeWidth() {
		BufferViewCache cache = new BufferViewCache(4);
		long offset = 0x1_0000_0040L;
		long range = 0x2_0000_0040L;
		long view = 0x3_0000_1234L;
		cache.remember(offset, range, 23, view);
		cache.remember(offset, range, 24, 2);
		assertEquals(view, cache.find(offset, range, 23));
		assertEquals(2, cache.find(offset, range, 24));
		assertEquals(0, cache.find((int) offset, range, 23));
		assertEquals(0, cache.find(offset, (int) range, 23));
		assertEquals(0, cache.find(offset, range, 25));
	}

	@Test
	void ownsEachCachedViewUntilDrain() {
		BufferViewCache cache = new BufferViewCache(2);
		cache.remember(0, 64, 1, 10);
		assertFalse(cache.isFull());
		cache.remember(0, 64, 2, 11);
		assertTrue(cache.isFull());
		List<Long> destroyed = new ArrayList<>();
		cache.destroyAll(destroyed::add);
		cache.destroyAll(destroyed::add);
		assertEquals(List.of(11L, 10L), destroyed);
		assertFalse(cache.isFull());
		assertEquals(0, cache.find(0, 64, 1));
	}
}
