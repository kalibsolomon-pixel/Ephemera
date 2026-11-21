package com.ephemera.core.terrain;

/**
 * Stable, allocation-free LSD radix sorter for translucent quad ordering.
 *
 * <p>Float keys are mapped to unsigned integers that preserve {@link Float#compare}
 * ordering (including {@code -0.0 < 0.0} and NaN last), then sorted with four 8-bit
 * counting passes. Passes in which every key shares the same byte are skipped, which
 * makes typical section-local distance sets cost two or three passes. Because each pass
 * is stable, ties keep their original index order, exactly like a stable merge sort.
 *
 * <p>Instances keep their scratch buffers and are not thread-safe; use one per thread.
 */
public final class TranslucentBucketSorter {
	private static final int RADIX_BITS = 8;
	private static final int BUCKETS = 1 << RADIX_BITS;
	private static final int PASSES = 32 / RADIX_BITS;

	private int[] keysA = new int[0];
	private int[] keysB = new int[0];
	private int[] indexA = new int[0];
	private int[] indexB = new int[0];
	private final int[] counts = new int[BUCKETS * PASSES];

	/** Maps a float to an int whose unsigned order matches {@link Float#compare}. */
	public static int sortableBits(float value) {
		int bits = Float.floatToRawIntBits(value);
		if (Float.isNaN(value)) {
			bits = 0x7fc00000;
		}
		return bits ^ ((bits >> 31) | 0x80000000);
	}

	/**
	 * Writes into {@code out[0..count)} the indices {@code 0..count-1} ordered by
	 * descending {@code keys[i]} (far to near); equal keys keep ascending index order.
	 */
	public void sortDescending(float[] keys, int count, int[] out) {
		sort(keys, count, out, true);
	}

	/** Like {@link #sortDescending} but ascending (near to far). */
	public void sortAscending(float[] keys, int count, int[] out) {
		sort(keys, count, out, false);
	}

	private void sort(float[] keys, int count, int[] out, boolean descending) {
		if (count < 0 || keys.length < count || out.length < count) {
			throw new IllegalArgumentException("buffer too small");
		}
		if (count == 0) {
			return;
		}
		ensureCapacity(count);
		int[] srcKeys = keysA;
		int[] srcIdx = indexA;
		int[] dstKeys = keysB;
		int[] dstIdx = indexB;
		int flip = descending ? -1 : 0;
		int[] c = counts;
		java.util.Arrays.fill(c, 0);
		for (int i = 0; i < count; i++) {
			int k = sortableBits(keys[i]) ^ flip;
			srcKeys[i] = k;
			srcIdx[i] = i;
			c[k & 0xFF]++;
			c[BUCKETS + ((k >>> 8) & 0xFF)]++;
			c[2 * BUCKETS + ((k >>> 16) & 0xFF)]++;
			c[3 * BUCKETS + (k >>> 24)]++;
		}

		for (int pass = 0; pass < PASSES; pass++) {
			int base = pass * BUCKETS;
			int shift = pass * RADIX_BITS;
			// Skip a pass when one bucket holds every key: it would not reorder anything.
			if (c[base + ((srcKeys[0] >>> shift) & 0xFF)] == count) {
				continue;
			}
			int sum = 0;
			for (int b = 0; b < BUCKETS; b++) {
				int n = c[base + b];
				c[base + b] = sum;
				sum += n;
			}
			for (int i = 0; i < count; i++) {
				int k = srcKeys[i];
				int pos = c[base + ((k >>> shift) & 0xFF)]++;
				dstKeys[pos] = k;
				dstIdx[pos] = srcIdx[i];
			}
			int[] t = srcKeys;
			srcKeys = dstKeys;
			dstKeys = t;
			t = srcIdx;
			srcIdx = dstIdx;
			dstIdx = t;
		}
		System.arraycopy(srcIdx, 0, out, 0, count);
	}

	private void ensureCapacity(int n) {
		if (keysA.length < n) {
			int size = Math.max(n, keysA.length + (keysA.length >> 1));
			keysA = new int[size];
			keysB = new int[size];
			indexA = new int[size];
			indexB = new int[size];
		}
	}
}
