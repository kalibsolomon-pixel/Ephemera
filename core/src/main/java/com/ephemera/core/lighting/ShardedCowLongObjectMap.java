package com.ephemera.core.lighting;

/**
 * 256-shard spatially-clustered lazy copy-on-write primitive-{@code long} to object map for
 * Minecraft's {@code DataLayerStorageMap} light section storage.
 *
 * <p>Vanilla {@code LayerLightSectionStorage.swapSectionMap()} clones the entire
 * {@code Long2ObjectOpenHashMap} (often 16k–64k slots = 256 KB–1 MB humongous arrays) on every
 * light update to publish an immutable {@code visibleSectionData} snapshot for meshing/saving
 * threads.
 *
 * <p>Minecraft packs section keys via {@code SectionPos.asLong(x, y, z)} with {@code y} in bits
 * {@code 0..19} and chunk column {@code (x, z)} in bits {@code 20..63}. Sharding by chunk column
 * ({@code key >>> 20}) into 256 tiny shards keeps all 24 vertical sections of a chunk inside ONE
 * shard, so loading, unloading, or relighting a chunk column clones 1 tiny shard (~512 B) instead
 * of 24 shards or the entire world map, while preserving exact immutable snapshot semantics and
 * lock-free concurrent reads.
 */
public final class ShardedCowLongObjectMap<V> {
	private static final int SHARD_BITS = 8;
	private static final int SHARD_COUNT = 1 << SHARD_BITS; // 256

	@SuppressWarnings("unchecked")
	private final Shard<V>[] shards;
	/** 256 ownership bits (4 primitive longs = zero heap allocation): bit i is 1 iff shards[i] is privately mutable. */
	private long owned0;
	private long owned1;
	private long owned2;
	private long owned3;
	/** Cached immutable snapshot view for zero-alloc consecutive snapshots when no writes intervened. */
	private ShardedCowLongObjectMap<V> cachedSnapshot;
	private boolean mutatedSinceSnapshot = true;

	@SuppressWarnings("unchecked")
	public ShardedCowLongObjectMap() {
		this.shards = (Shard<V>[]) new Shard<?>[SHARD_COUNT];
	}

	private ShardedCowLongObjectMap(Shard<V>[] shards) {
		this.shards = shards;
		this.cachedSnapshot = this;
		this.mutatedSinceSnapshot = false;
	}

	private static int shardIndex(long key) {
		// Cluster by chunk column (bits 20..63 of SectionPos.asLong) so all vertical sections of
		// the same chunk share one shard.
		long col = key >>> 20;
		long h = col ^ (col >>> 22);
		h *= 0x9E3779B97F4A7C15L;
		return (int) (h >>> 56);
	}

	private boolean isOwned(int idx) {
		long bit = 1L << idx;
		return switch (idx >>> 6) {
			case 0 -> (owned0 & bit) != 0L;
			case 1 -> (owned1 & bit) != 0L;
			case 2 -> (owned2 & bit) != 0L;
			default -> (owned3 & bit) != 0L;
		};
	}

	private void markOwned(int idx) {
		long bit = 1L << idx;
		switch (idx >>> 6) {
			case 0 -> owned0 |= bit;
			case 1 -> owned1 |= bit;
			case 2 -> owned2 |= bit;
			default -> owned3 |= bit;
		}
	}

	/** Lock-free read safe on any immutable snapshot returned by {@link #snapshot()}. */
	public V get(long key) {
		Shard<V> shard = shards[shardIndex(key)];
		return shard == null ? null : shard.get(key);
	}

	/** Lock-free membership check safe on any immutable snapshot returned by {@link #snapshot()}. */
	public boolean containsKey(long key) {
		Shard<V> shard = shards[shardIndex(key)];
		return shard != null && shard.containsKey(key);
	}

	/** Single-writer mutation: clones only the target shard if currently shared with a snapshot. */
	public V put(long key, V value) {
		int idx = shardIndex(key);
		Shard<V> shard = ensureMutable(idx);
		V old = shard.put(key, value);
		mutatedSinceSnapshot = true;
		cachedSnapshot = null;
		return old;
	}

	/** Single-writer removal: avoids cloning when absent and releases empty shards immediately. */
	public V remove(long key) {
		int idx = shardIndex(key);
		Shard<V> existing = shards[idx];
		if (existing == null || !existing.containsKey(key)) {
			return null;
		}
		mutatedSinceSnapshot = true;
		cachedSnapshot = null;
		if (existing.size == 1) {
			V old = existing.get(key);
			shards[idx] = null;
			return old;
		}
		Shard<V> shard = ensureMutable(idx);
		return shard.remove(key);
	}

	/**
	 * O(1) immutable snapshot: reuses the cached immutable shard pointer array when no writes
	 * occurred since the last snapshot, otherwise copies only the 256-element pointer array (~1 KB)
	 * and clears ownership bits so future mutations copy-on-write only touched column shards.
	 */
	public ShardedCowLongObjectMap<V> snapshot() {
		if (!mutatedSinceSnapshot && cachedSnapshot != null) {
			return cachedSnapshot;
		}
		owned0 = 0L;
		owned1 = 0L;
		owned2 = 0L;
		owned3 = 0L;
		ShardedCowLongObjectMap<V> frozen = new ShardedCowLongObjectMap<>(shards.clone());
		cachedSnapshot = frozen;
		mutatedSinceSnapshot = false;
		return frozen;
	}

	public int size() {
		int total = 0;
		for (Shard<V> s : shards) {
			if (s != null) {
				total += s.size;
			}
		}
		return total;
	}

	public boolean isEmpty() {
		for (Shard<V> s : shards) {
			if (s != null && s.size > 0) {
				return false;
			}
		}
		return true;
	}

	private Shard<V> ensureMutable(int idx) {
		Shard<V> cur = shards[idx];
		if (cur == null) {
			Shard<V> fresh = new Shard<>(4);
			shards[idx] = fresh;
			markOwned(idx);
			return fresh;
		}
		if (!isOwned(idx)) {
			Shard<V> copy = cur.copy();
			shards[idx] = copy;
			markOwned(idx);
			return copy;
		}
		return cur;
	}

	/** Compact open-addressing primitive-long map for one 1/256th column shard. */
	private static final class Shard<V> {
		private long[] keys;
		private Object[] values;
		private int mask;
		private int maxFill;
		private int size;
		private boolean containsZero;
		private V zeroValue;

		Shard(int initialCapacity) {
			int cap = Math.max(4, Integer.highestOneBit(initialCapacity - 1) << 1);
			this.keys = new long[cap];
			this.values = new Object[cap];
			this.mask = cap - 1;
			this.maxFill = Math.max(1, (cap * 3) >>> 2); // 0.75 load factor
		}

		private Shard(long[] keys, Object[] values, int mask, int maxFill, int size, boolean containsZero, V zeroValue) {
			this.keys = keys;
			this.values = values;
			this.mask = mask;
			this.maxFill = maxFill;
			this.size = size;
			this.containsZero = containsZero;
			this.zeroValue = zeroValue;
		}

		Shard<V> copy() {
			return new Shard<>(keys.clone(), values.clone(), mask, maxFill, size, containsZero, zeroValue);
		}

		private static int mix(long k) {
			long h = k * 0x9E3779B97F4A7C15L;
			return (int) (h ^ (h >>> 32));
		}

		@SuppressWarnings("unchecked")
		V get(long key) {
			if (key == 0L) {
				return containsZero ? zeroValue : null;
			}
			long[] ks = this.keys;
			int m = this.mask;
			int pos = mix(key) & m;
			long curr = ks[pos];
			if (curr == 0L) {
				return null;
			}
			if (curr == key) {
				return (V) values[pos];
			}
			while (true) {
				pos = (pos + 1) & m;
				curr = ks[pos];
				if (curr == 0L) {
					return null;
				}
				if (curr == key) {
					return (V) values[pos];
				}
			}
		}

		boolean containsKey(long key) {
			if (key == 0L) {
				return containsZero;
			}
			long[] ks = this.keys;
			int m = this.mask;
			int pos = mix(key) & m;
			long curr = ks[pos];
			if (curr == 0L) {
				return false;
			}
			if (curr == key) {
				return true;
			}
			while (true) {
				pos = (pos + 1) & m;
				curr = ks[pos];
				if (curr == 0L) {
					return false;
				}
				if (curr == key) {
					return true;
				}
			}
		}

		@SuppressWarnings("unchecked")
		V put(long key, V value) {
			if (key == 0L) {
				V old = containsZero ? zeroValue : null;
				if (!containsZero) {
					containsZero = true;
					size++;
				}
				zeroValue = value;
				return old;
			}
			long[] ks = this.keys;
			int m = this.mask;
			int pos = mix(key) & m;
			long curr = ks[pos];
			if (curr != 0L) {
				if (curr == key) {
					V old = (V) values[pos];
					values[pos] = value;
					return old;
				}
				while (true) {
					pos = (pos + 1) & m;
					curr = ks[pos];
					if (curr == 0L) {
						break;
					}
					if (curr == key) {
						V old = (V) values[pos];
						values[pos] = value;
						return old;
					}
				}
			}
			ks[pos] = key;
			values[pos] = value;
			if (++size >= maxFill) {
				rehash(ks.length << 1);
			}
			return null;
		}

		@SuppressWarnings("unchecked")
		V remove(long key) {
			if (key == 0L) {
				if (!containsZero) {
					return null;
				}
				V old = zeroValue;
				containsZero = false;
				zeroValue = null;
				size--;
				return old;
			}
			long[] ks = this.keys;
			int m = this.mask;
			int pos = mix(key) & m;
			long curr = ks[pos];
			if (curr == 0L) {
				return null;
			}
			while (curr != key) {
				pos = (pos + 1) & m;
				curr = ks[pos];
				if (curr == 0L) {
					return null;
				}
			}
			V old = (V) values[pos];
			size--;
			shiftKeys(pos);
			return old;
		}

		private void shiftKeys(int pos) {
			long[] ks = this.keys;
			Object[] vs = this.values;
			int m = this.mask;
			while (true) {
				int last = pos;
				pos = (pos + 1) & m;
				long curr;
				while (true) {
					curr = ks[pos];
					if (curr == 0L) {
						ks[last] = 0L;
						vs[last] = null;
						return;
					}
					int slot = mix(curr) & m;
					if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) {
						break;
					}
					pos = (pos + 1) & m;
				}
				ks[last] = curr;
				vs[last] = vs[pos];
			}
		}

		private void rehash(int newCap) {
			long[] oldKeys = this.keys;
			Object[] oldVals = this.values;
			long[] newKeys = new long[newCap];
			Object[] newVals = new Object[newCap];
			int newMask = newCap - 1;
			for (int i = 0; i < oldKeys.length; i++) {
				long k = oldKeys[i];
				if (k != 0L) {
					int pos = mix(k) & newMask;
					while (newKeys[pos] != 0L) {
						pos = (pos + 1) & newMask;
					}
					newKeys[pos] = k;
					newVals[pos] = oldVals[i];
				}
			}
			this.keys = newKeys;
			this.values = newVals;
			this.mask = newMask;
			this.maxFill = Math.max(1, (newCap * 3) >>> 2);
		}
	}
}
