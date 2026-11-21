package com.ephemera.core.gui;

import java.util.Arrays;

/**
 * Append-only set of integer rectangles stored as a flat {@code int[]}
 * ({@code left, top, right, bottom} per entry) with a running union box.
 * <p>
 * {@link #intersectsAny} answers "does any stored rectangle strictly overlap the query" with the
 * exact predicate of vanilla {@code ScreenRectangle.intersects}
 * ({@code left < otherRight && right > otherLeft && top < otherBottom && bottom > otherTop}),
 * where {@code right}/{@code bottom} are {@code x + width}/{@code y + height} computed in int math.
 * A query that misses the union box cannot hit any member, so most layered-GUI queries are O(1)
 * and the rest are a tight primitive loop. No allocation happens per query.
 */
public final class GuiBoundsIndex {
	private static final int STRIDE = 4;

	private int[] rects;
	private int size;
	private int unionLeft;
	private int unionTop;
	private int unionRight;
	private int unionBottom;

	public GuiBoundsIndex() {
		this(16);
	}

	public GuiBoundsIndex(int initialCapacity) {
		this.rects = new int[Math.max(1, initialCapacity) * STRIDE];
	}

	public int size() {
		return size;
	}

	public void clear() {
		size = 0;
	}

	public void add(int left, int top, int right, int bottom) {
		int offset = size * STRIDE;
		if (offset == rects.length) {
			rects = Arrays.copyOf(rects, rects.length * 2);
		}
		rects[offset] = left;
		rects[offset + 1] = top;
		rects[offset + 2] = right;
		rects[offset + 3] = bottom;
		if (size == 0) {
			unionLeft = left;
			unionTop = top;
			unionRight = right;
			unionBottom = bottom;
		} else {
			if (left < unionLeft) unionLeft = left;
			if (top < unionTop) unionTop = top;
			if (right > unionRight) unionRight = right;
			if (bottom > unionBottom) unionBottom = bottom;
		}
		size++;
	}

	public boolean intersectsAny(int left, int top, int right, int bottom) {
		if (size == 0
			|| unionLeft >= right || unionRight <= left
			|| unionTop >= bottom || unionBottom <= top) {
			return false;
		}
		int[] rects = this.rects;
		for (int i = 0, end = size * STRIDE; i < end; i += STRIDE) {
			if (rects[i] < right && rects[i + 2] > left && rects[i + 1] < bottom && rects[i + 3] > top) {
				return true;
			}
		}
		return false;
	}
}
