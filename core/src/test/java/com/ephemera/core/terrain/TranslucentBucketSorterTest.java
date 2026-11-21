package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TranslucentBucketSorterTest {
	private static int[] referenceDescending(float[] keys, int n) {
		Integer[] idx = new Integer[n];
		for (int i = 0; i < n; i++) {
			idx[i] = i;
		}
		// Arrays.sort on objects is a stable merge sort, like vanilla's IntArrays.mergeSort.
		Arrays.sort(idx, (a, b) -> Float.compare(keys[b], keys[a]));
		return Arrays.stream(idx).mapToInt(Integer::intValue).toArray();
	}

	@Test
	void matchesStableReferenceOnRandomDistances() {
		Random rnd = new Random(5);
		TranslucentBucketSorter sorter = new TranslucentBucketSorter();
		for (int n : new int[] {1, 2, 3, 17, 256, 1000, 4096, 12_345}) {
			float[] keys = new float[n];
			for (int i = 0; i < n; i++) {
				keys[i] = rnd.nextFloat() * 768f;
			}
			int[] out = new int[n];
			sorter.sortDescending(keys, n, out);
			assertArrayEquals(referenceDescending(keys, n), out, "n=" + n);
		}
	}

	@Test
	void tiesKeepAscendingIndexOrder() {
		float[] keys = {4, 1, 4, 4, 2, 1};
		int[] out = new int[6];
		new TranslucentBucketSorter().sortDescending(keys, 6, out);
		assertArrayEquals(new int[] {0, 2, 3, 4, 1, 5}, out);
	}

	@Test
	void handlesSpecialFloatsLikeFloatCompare() {
		float[] keys = {0.0f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY, -3f, 1e-30f, Float.MIN_VALUE, -Float.NaN};
		int n = keys.length;
		int[] out = new int[n];
		new TranslucentBucketSorter().sortDescending(keys, n, out);
		assertArrayEquals(referenceDescending(keys, n), out);

		Integer[] asc = new Integer[n];
		for (int i = 0; i < n; i++) {
			asc[i] = i;
		}
		Arrays.sort(asc, (a, b) -> Float.compare(keys[a], keys[b]));
		new TranslucentBucketSorter().sortAscending(keys, n, out);
		assertArrayEquals(Arrays.stream(asc).mapToInt(Integer::intValue).toArray(), out);
	}

	@Test
	void reusesScratchAcrossSizes() {
		TranslucentBucketSorter sorter = new TranslucentBucketSorter();
		Random rnd = new Random(11);
		for (int round = 0; round < 50; round++) {
			int n = 1 + rnd.nextInt(3000);
			float[] keys = new float[n];
			for (int i = 0; i < n; i++) {
				keys[i] = rnd.nextInt(64); // many duplicates
			}
			int[] out = new int[n];
			sorter.sortDescending(keys, n, out);
			assertArrayEquals(referenceDescending(keys, n), out);
		}
	}

	@Test
	void sortableBitsPreservesOrder() {
		float[] values = {Float.NEGATIVE_INFINITY, -2f, -0.0f, 0.0f, Float.MIN_VALUE, 1f, Float.POSITIVE_INFINITY, Float.NaN};
		for (int i = 1; i < values.length; i++) {
			assertTrue(Integer.compareUnsigned(TranslucentBucketSorter.sortableBits(values[i - 1]),
				TranslucentBucketSorter.sortableBits(values[i])) < 0, "at " + i);
		}
	}
}
