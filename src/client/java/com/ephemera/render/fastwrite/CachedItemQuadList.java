package com.ephemera.render.fastwrite;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Immutable array-backed {@link BakedQuad} list shared directly across frames by
 * {@code CuboidItemModelWrapper} without per-frame {@code ArrayList.addAll} copies.
 * Carries precomputed translucency and position-validity flags so item submission and
 * {@link FastQuadWriter} skip per-quad scans and {@code List.get} bounds checks.
 */
public final class CachedItemQuadList extends AbstractList<BakedQuad> implements RandomAccess {
	public final BakedQuad[] array;
	public final boolean hasTranslucency;
	public final boolean allPositionsNonNull;

	public CachedItemQuadList(BakedQuad[] array, boolean hasTranslucency, boolean allPositionsNonNull) {
		this.array = array;
		this.hasTranslucency = hasTranslucency;
		this.allPositionsNonNull = allPositionsNonNull;
	}

	public static CachedItemQuadList of(List<BakedQuad> source, boolean hasTranslucency) {
		BakedQuad[] arr = source.toArray(new BakedQuad[0]);
		boolean nonNull = true;
		for (BakedQuad q : arr) {
			if (q == null || q.position0() == null || q.position1() == null || q.position2() == null || q.position3() == null) {
				nonNull = false;
				break;
			}
		}
		return new CachedItemQuadList(arr, hasTranslucency, nonNull);
	}

	@Override
	public BakedQuad get(int index) {
		return array[index];
	}

	@Override
	public int size() {
		return array.length;
	}
}
