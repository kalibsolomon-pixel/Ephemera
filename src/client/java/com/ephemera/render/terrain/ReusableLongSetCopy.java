package com.ephemera.render.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Zero-rehash, zero-allocation {@link System#arraycopy} copier for fastutil {@link LongOpenHashSet}
 * snapshots. Reuses the destination backing {@code long[]} whenever its table size matches, turning
 * {@code SectionOcclusionGraph.scheduleFullUpdate()}'s 256 KB {@code clone()} allocations into a
 * ~5 us raw array copy without re-hashing tens of thousands of section keys on the render thread.
 */
public final class ReusableLongSetCopy {
	private static final VarHandle VH_KEY;
	private static final VarHandle VH_MASK;
	private static final VarHandle VH_CONTAINS_NULL;
	private static final VarHandle VH_N;
	private static final VarHandle VH_MAX_FILL;
	private static final VarHandle VH_SIZE;
	private static final boolean AVAILABLE;

	static {
		VarHandle k = null, m = null, cn = null, n = null, mf = null, sz = null;
		boolean ok = false;
		try {
			MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LongOpenHashSet.class, MethodHandles.lookup());
			k = lookup.findVarHandle(LongOpenHashSet.class, "key", long[].class);
			m = lookup.findVarHandle(LongOpenHashSet.class, "mask", int.class);
			cn = lookup.findVarHandle(LongOpenHashSet.class, "containsNull", boolean.class);
			n = lookup.findVarHandle(LongOpenHashSet.class, "n", int.class);
			mf = lookup.findVarHandle(LongOpenHashSet.class, "maxFill", int.class);
			sz = lookup.findVarHandle(LongOpenHashSet.class, "size", int.class);
			ok = true;
		} catch (Throwable ignored) {
			ok = false;
		}
		VH_KEY = k;
		VH_MASK = m;
		VH_CONTAINS_NULL = cn;
		VH_N = n;
		VH_MAX_FILL = mf;
		VH_SIZE = sz;
		AVAILABLE = ok;
	}

	private ReusableLongSetCopy() {}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/**
	 * Copies the exact hash-table state of {@code src} into {@code dst} using a single
	 * {@link System#arraycopy}, reusing {@code dst}'s {@code long[]} when lengths match.
	 *
	 * @return {@code true} if the fast array-copy succeeded; {@code false} if caller should fall back to {@code clone()}
	 */
	public static boolean copyInto(LongOpenHashSet src, LongOpenHashSet dst) {
		if (!AVAILABLE || src == null || dst == null || src.getClass() != LongOpenHashSet.class) {
			return false;
		}
		long[] srcKey = (long[]) VH_KEY.get(src);
		if (srcKey == null) {
			return false;
		}
		int len = srcKey.length;
		long[] dstKey = (long[]) VH_KEY.get(dst);
		if (dstKey == null || dstKey.length != len) {
			dstKey = new long[len];
			VH_KEY.set(dst, dstKey);
		}
		System.arraycopy(srcKey, 0, dstKey, 0, len);
		VH_MASK.set(dst, (int) VH_MASK.get(src));
		VH_CONTAINS_NULL.set(dst, (boolean) VH_CONTAINS_NULL.get(src));
		VH_N.set(dst, (int) VH_N.get(src));
		VH_MAX_FILL.set(dst, (int) VH_MAX_FILL.get(src));
		VH_SIZE.set(dst, (int) VH_SIZE.get(src));
		return true;
	}
}
