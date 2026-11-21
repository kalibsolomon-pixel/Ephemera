package com.ephemera.render.terrain;

import com.ephemera.core.terrain.TranslucentBucketSorter;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Vector3f;

/**
 * Drop-in replacement for {@code VertexSorting.byDistance(x, y, z)} used for translucent
 * section geometry. Distances are computed with the same JOML call vanilla uses
 * ({@code origin.distanceSquared(centroid)}), and the stable radix sort yields exactly the
 * order of vanilla's stable descending merge sort, without its boxed comparator and
 * O(n log n) comparisons. Scratch buffers are per thread; only the returned order array is
 * allocated, as the {@link VertexSorting} contract requires a fresh array.
 */
public final class FastVertexSorting implements VertexSorting {
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	private final Vector3f origin;

	public FastVertexSorting(float x, float y, float z) {
		this.origin = new Vector3f(x, y, z);
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
		for (int i = 0; i < n; i++) {
			keys[i] = origin.distanceSquared(centroids.get(i, v));
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
