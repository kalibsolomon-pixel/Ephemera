package com.ephemera.core.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FramebufferLookupTest {
	@Test
	void matchesAllFourKeyComponents() {
		FramebufferLookup cache = new FramebufferLookup();
		cache.remember(5, 0, 6, 0, 100);
		cache.remember(5, 1, 6, 0, 101);
		cache.remember(5, 0, 6, 1, 102);
		cache.remember(5, 0, 0, 0, 103);
		assertEquals(100, cache.find(5, 0, 6, 0));
		assertEquals(101, cache.find(5, 1, 6, 0));
		assertEquals(102, cache.find(5, 0, 6, 1));
		assertEquals(103, cache.find(5, 0, 0, 0));
		assertEquals(0, cache.find(6, 0, 5, 0));
	}

	@Test
	void wrapsAndInvalidatesByDeletedFramebufferHandle() {
		FramebufferLookup cache = new FramebufferLookup();
		for (int color = 1; color <= 5; color++) {
			cache.remember(color, 0, 0, 0, 100 + color);
		}
		assertEquals(0, cache.find(1, 0, 0, 0));
		assertEquals(102, cache.find(2, 0, 0, 0));
		cache.remove(104);
		assertEquals(0, cache.find(4, 0, 0, 0));
		assertEquals(105, cache.find(5, 0, 0, 0));
		cache.remember(4, 0, 0, 0, 204);
		assertEquals(204, cache.find(4, 0, 0, 0));
	}
}
