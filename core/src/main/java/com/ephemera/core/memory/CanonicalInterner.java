package com.ephemera.core.memory;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * Bounded, open-addressing canonical interning table designed to eliminate duplicate immutable
 * runtime structures (block-state caches, {@code VoxelShape}s, axis coordinate lists, face arrays,
 * baked quads, material records) without the eviction thrashing of small direct-mapped tables or
 * the heavy per-entry overhead of {@code WeakHashMap}.
 *
 * <p>Supports {@link #clear()} after bootstrap or resource-pack reload so transient interning
 * tables retain zero heap overhead and never pin stale objects across reloads.
 *
 * <p>Synchronized internally because Minecraft block-state initialization and resource-pack
 * model baking can run across worker threads.
 */
public final class CanonicalInterner<T> {
	private static final int MIN_CAPACITY = 64;
	private static final float LOAD_FACTOR = 0.65f;

	private final ToIntFunction<T> hasher;
	private final BiPredicate<T, T> equals;
	private final int maxCapacity;

	private Object[] table;
	private int[] hashes;
	private int mask;
	private int size;
	private int threshold;

	private long lookups;
	private long hits;

	public CanonicalInterner(int initialCapacity, int maxCapacity,
							 ToIntFunction<T> hasher, BiPredicate<T, T> equals) {
		int cap = nextPowerOfTwo(Math.max(MIN_CAPACITY, initialCapacity));
		int max = nextPowerOfTwo(Math.max(cap, maxCapacity));
		this.hasher = Objects.requireNonNull(hasher, "hasher");
		this.equals = Objects.requireNonNull(equals, "equals");
		this.maxCapacity = max;
		allocate(cap);
	}

	private void allocate(int capacity) {
		this.table = new Object[capacity];
		this.hashes = new int[capacity];
		this.mask = capacity - 1;
		this.size = 0;
		this.threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
	}

	@SuppressWarnings("unchecked")
	public synchronized T intern(T candidate) {
		if (candidate == null) {
			return null;
		}
		lookups++;
		int h = mix(hasher.applyAsInt(candidate));
		Object[] tab = table;
		int[] hs = hashes;
		int m = mask;
		int slot = h & m;
		int emptySlot = -1;
		for (int probes = 0; probes < tab.length; probes++) {
			Object existing = tab[slot];
			if (existing == null) {
				emptySlot = slot;
				break;
			}
			if (existing == candidate) {
				hits++;
				return candidate;
			}
			if (hs[slot] == h && equals.test((T) existing, candidate)) {
				hits++;
				return (T) existing;
			}
			slot = (slot + 1) & m;
		}

		// Table at hard cap: avoid unbounded growth while keeping load <= LOAD_FACTOR so probing stays O(1).
		if (emptySlot < 0 || size >= threshold) {
			if (tab.length < maxCapacity) {
				grow();
				return insertAfterGrow(candidate, h);
			}
			// Direct-mapped eviction at hard maxCapacity: only overwrite an already-occupied primary slot
			// so size never climbs past threshold (preserving >=35% empty slots for fast probe termination).
			int evictSlot = h & m;
			if (tab[evictSlot] != null) {
				tab[evictSlot] = candidate;
				hs[evictSlot] = h;
			}
			return candidate;
		}

		tab[emptySlot] = candidate;
		hs[emptySlot] = h;
		size++;
		return candidate;
	}

	private T insertAfterGrow(T candidate, int h) {
		Object[] tab = table;
		int[] hs = hashes;
		int m = mask;
		int slot = h & m;
		while (tab[slot] != null) {
			slot = (slot + 1) & m;
		}
		tab[slot] = candidate;
		hs[slot] = h;
		size++;
		return candidate;
	}

	private void grow() {
		Object[] oldTable = table;
		int[] oldHashes = hashes;
		int newCap = Math.min(maxCapacity, oldTable.length << 1);
		Object[] newTable = new Object[newCap];
		int[] newHashes = new int[newCap];
		int newMask = newCap - 1;

		for (int i = 0; i < oldTable.length; i++) {
			Object item = oldTable[i];
			if (item != null) {
				int h = oldHashes[i];
				int slot = h & newMask;
				while (newTable[slot] != null) {
					slot = (slot + 1) & newMask;
				}
				newTable[slot] = item;
				newHashes[slot] = h;
			}
		}

		this.table = newTable;
		this.hashes = newHashes;
		this.mask = newMask;
		this.threshold = Math.max(1, (int) (newCap * LOAD_FACTOR));
	}

	/**
	 * Drops all entries and shrinks backing arrays back to {@link #MIN_CAPACITY} so post-reload /
	 * post-bootstrap memory overhead drops to nearly zero and stale references cannot leak.
	 */
	public synchronized void clear() {
		if (table.length > MIN_CAPACITY) {
			allocate(MIN_CAPACITY);
		} else {
			Arrays.fill(table, null);
			Arrays.fill(hashes, 0);
			size = 0;
		}
	}

	public synchronized int size() {
		return size;
	}

	public synchronized int capacity() {
		return table.length;
	}

	public synchronized long lookups() {
		return lookups;
	}

	public synchronized long deduplicatedCount() {
		return hits;
	}

	private static int mix(int h) {
		h ^= (h >>> 16);
		h *= 0x7FEB352D;
		h ^= (h >>> 15);
		h *= 0x846CA68B;
		h ^= (h >>> 16);
		return h;
	}

	private static int nextPowerOfTwo(int v) {
		int n = v - 1;
		n |= n >>> 1;
		n |= n >>> 2;
		n |= n >>> 4;
		n |= n >>> 8;
		n |= n >>> 16;
		return (n < 1) ? 1 : (n >= (1 << 30)) ? (1 << 30) : n + 1;
	}
}
