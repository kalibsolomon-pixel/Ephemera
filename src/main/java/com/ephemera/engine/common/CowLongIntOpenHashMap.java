package com.ephemera.engine.common;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Copy-on-write {@link Long2IntOpenHashMap} for {@code SkyDataLayerStorageMap.topSections}.
 *
 * <p>Over 95% of sky-light section updates do not change the highest non-empty section Y of any
 * chunk column, yet vanilla {@code SkyDataLayerStorageMap.copy()} clones the entire primitive
 * {@code long[]}/{@code int[]} backing table on every {@code swapSectionMap()}. Returning the last
 * immutable snapshot when no writes intervened eliminates ~58 MB / 4s of large array clones on
 * the light worker thread.
 */
public final class CowLongIntOpenHashMap extends Long2IntOpenHashMap {
	private boolean dirty = true;
	private CowLongIntOpenHashMap cachedSnapshot;

	public CowLongIntOpenHashMap(Long2IntOpenHashMap source) {
		super(source);
		this.defaultReturnValue(source.defaultReturnValue());
	}

	@Override
	public int put(long k, int v) {
		this.dirty = true;
		this.cachedSnapshot = null;
		return super.put(k, v);
	}

	@Override
	public int remove(long k) {
		if (!containsKey(k)) {
			return this.defRetValue;
		}
		this.dirty = true;
		this.cachedSnapshot = null;
		return super.remove(k);
	}

	@Override
	public void clear() {
		if (this.size == 0) {
			return;
		}
		this.dirty = true;
		this.cachedSnapshot = null;
		super.clear();
	}

	@Override
	public void defaultReturnValue(int rv) {
		if (this.defRetValue != rv) {
			this.dirty = true;
			this.cachedSnapshot = null;
		}
		super.defaultReturnValue(rv);
	}

	@Override
	public CowLongIntOpenHashMap clone() {
		if (!this.dirty && this.cachedSnapshot != null) {
			return this.cachedSnapshot;
		}
		CowLongIntOpenHashMap copy = (CowLongIntOpenHashMap) super.clone();
		copy.dirty = false;
		copy.cachedSnapshot = copy;
		this.cachedSnapshot = copy;
		this.dirty = false;
		return copy;
	}
}
