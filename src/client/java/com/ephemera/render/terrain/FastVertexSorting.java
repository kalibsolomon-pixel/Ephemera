package com.ephemera.render.terrain;

import com.ephemera.core.terrain.TranslucentBucketSorter;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Vector3f;

/**
 * Drop-in replacement for {@code VertexSorting.byDistance(x, y, z)} used for translucent
 * section geometry. Distances are computed with the same primitive JOML overload vanilla's
 * {@code origin.distanceSquared(centroid)} reduces to, and the stable radix sort yields exactly
 * the order of vanilla's stable descending merge sort, without its boxed comparator and
 * O(n log n) comparisons. Scratch buffers are per thread; only the returned order array is
 * allocated, as the {@link VertexSorting} contract requires a fresh array.
 */
public final class FastVertexSorting implements VertexSorting {
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
	private static final ThreadLocal<FastVertexSorting> REUSABLE = ThreadLocal.withInitial(() -> new FastVertexSorting(0.0f, 0.0f, 0.0f));

	/**
	 * Returns this thread's reusable sorting instance re-aimed at the given origin. Safe because
	 * {@link #sort} runs synchronously on the calling thread (chunk-builder worker or render
	 * thread), uses only thread-local scratch, and the instance never escapes the sort call.
	 */
	public static FastVertexSorting forCurrentThread(float x, float y, float z) {
		FastVertexSorting sorter = REUSABLE.get();
		sorter.originX = x;
		sorter.originY = y;
		sorter.originZ = z;
		return sorter;
	}

	private float originX;
	private float originY;
	private float originZ;

	/** Direct construction for call sites whose sorter lifetime is not provably synchronous. */
	public FastVertexSorting(float x, float y, float z) {
		this.originX = x;
		this.originY = y;
		this.originZ = z;
	}

	@Override
	public int[] sort(CompactVectorArray centroids) {
		int n = centroids.size();
		int[] out = new int[n];
		if (n == 0) {
			return out;
		}
		Scratch s = SCRATCH.get();
		float[] keys = s.keys(n);
		Vector3f v = s.centroid;
		float ox = originX;
		float oy = originY;
		float oz = originZ;
		for (int i = 0; i < n; i++) {
			centroids.get(i, v);
			float dx = v.x - ox;
			float dy = v.y - oy;
			float dz = v.z - oz;
			// org.joml.Math.fma follows the same USE_MATH_FMA option as JOML's
			// Vector3fc.distanceSquared (non-FMA a*b+c by default), so key values — and hence
			// tie-breaking order — are bit-identical to vanilla's sort.
			keys[i] = org.joml.Math.fma(dx, dx, org.joml.Math.fma(dy, dy, dz * dz));
		}
		s.sorter.sortDescending(keys, n, out);
		return out;
	}

	private static final class Scratch {
		final TranslucentBucketSorter sorter = new TranslucentBucketSorter();
		final Vector3f centroid = new Vector3f();
		float[] keys = new float[0];

		float[] keys(int n) {
			if (keys.length < n) {
				keys = new float[Math.max(n, keys.length + (keys.length >> 1))];
			}
			return keys;
		}
	}
}
