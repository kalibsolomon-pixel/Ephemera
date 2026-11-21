package com.ephemera.core.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModelIdentityKeyTest {
	/** Counts hashCode calls so the tests can prove the list is walked once, not per lookup. */
	private static final class Counted {
		final String id;
		final AtomicInteger hashCalls;

		Counted(String id, AtomicInteger hashCalls) {
			this.id = id;
			this.hashCalls = hashCalls;
		}

		@Override
		public int hashCode() {
			hashCalls.incrementAndGet();
			return id.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Counted c && c.id.equals(id);
		}
	}

	private static List<Object> list(AtomicInteger calls, String... ids) {
		List<Object> elements = new ArrayList<>();
		for (String id : ids) {
			elements.add(new Counted(id, calls));
		}
		return elements;
	}

	@Test
	void equalsAndHashMatchTheWrappedListSemantics() {
		AtomicInteger calls = new AtomicInteger();
		ModelIdentityKey a = new ModelIdentityKey(list(calls, "sword", "foil"));
		ModelIdentityKey b = new ModelIdentityKey(list(calls, "sword", "foil"));
		ModelIdentityKey c = new ModelIdentityKey(list(calls, "sword", "plain"));
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
		assertNotEquals(a, null);
		assertNotEquals(a, "sword");
		assertEquals(new ArrayList<>(list(calls, "sword", "foil")).hashCode(), a.hashCode());
	}

	@Test
	void hashIsComputedOncePerChange() {
		AtomicInteger calls = new AtomicInteger();
		ModelIdentityKey key = new ModelIdentityKey(list(calls, "a", "b", "c"));
		Set<Object> set = new HashSet<>();
		Map<Object, String> map = new HashMap<>();
		for (int i = 0; i < 100; i++) {
			set.add(key);
			map.put(key, "slot");
			assertEquals("slot", map.get(key));
			assertTrue(set.contains(key));
		}
		assertEquals(3, calls.get(), "three elements hashed exactly once");
	}

	@Test
	void sameInstanceLookupNeverWalksTheList() {
		AtomicInteger calls = new AtomicInteger();
		ModelIdentityKey key = new ModelIdentityKey(list(calls, "a", "b"));
		Map<Object, String> map = new HashMap<>();
		map.put(key, "x");
		int afterInsert = calls.get();
		for (int i = 0; i < 50; i++) {
			assertEquals("x", map.get(key));
		}
		assertEquals(afterInsert, calls.get());
	}

	@Test
	void distinctInstancesWithEqualContentFindEachOther() {
		AtomicInteger calls = new AtomicInteger();
		Map<Object, String> map = new HashMap<>();
		map.put(new ModelIdentityKey(list(calls, "stone")), "slot");
		assertEquals("slot", map.get(new ModelIdentityKey(list(calls, "stone"))));
		assertNull(map.get(new ModelIdentityKey(list(calls, "dirt"))));
	}

	@Test
	void appendingInvalidatesHashAndMemo() {
		AtomicInteger calls = new AtomicInteger();
		List<Object> elements = list(calls, "a");
		ModelIdentityKey key = new ModelIdentityKey(elements);
		int before = key.hashCode();
		Object owner = new Object();
		key.remember(owner, "slot");
		assertEquals("slot", key.memoFor(owner));

		elements.add(new Counted("b", calls));
		key.elementAppended();

		assertNotEquals(before, key.hashCode());
		assertEquals(new ArrayList<>(elements).hashCode(), key.hashCode());
		assertNull(key.memoFor(owner));
	}

	@Test
	void memoIsBoundToItsOwner() {
		ModelIdentityKey key = new ModelIdentityKey(new ArrayList<>());
		Object first = new Object();
		Object second = new Object();
		assertNull(key.memoFor(first));
		key.remember(first, "one");
		assertEquals("one", key.memoFor(first));
		assertNull(key.memoFor(second));
		key.remember(second, "two");
		assertNull(key.memoFor(first));
		assertEquals("two", key.memoFor(second));
	}

	@Test
	void emptyIdentityIsAValidKey() {
		ModelIdentityKey a = new ModelIdentityKey(new ArrayList<>());
		ModelIdentityKey b = new ModelIdentityKey(new ArrayList<>());
		assertEquals(a, b);
		assertEquals(new ArrayList<>().hashCode(), a.hashCode());
	}
}
