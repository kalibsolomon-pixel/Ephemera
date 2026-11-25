package com.ephemera.core.gui;

import java.util.ArrayList;

/**
 * An {@link ArrayList} that keeps a {@link GuiBoundsIndex} of its members' rectangles up to date as they are
 * appended, so "does any member overlap this rectangle" needs no side table keyed by the list.
 * <p>
 * Appends through {@link #add(Object)} extend the index in place. Any other mutation (insert, remove, bulk
 * add, sort, {@code set}) is detected through {@code modCount} or the {@code set} override and the index is
 * rebuilt from the list on the next query, so answers always match a linear scan of the current contents.
 * The index and its backing array live as long as the list.
 */
public final class IndexedRectList<T> extends ArrayList<T> {
	/** Writes the member's rectangle into the index, or nothing when the member has no bounds. */
	@FunctionalInterface
	public interface BoundsSink<T> {
		void addTo(T member, GuiBoundsIndex index);
	}

	private final BoundsSink<? super T> sink;
	private final GuiBoundsIndex index = new GuiBoundsIndex(16);
	private int indexedModCount;

	public IndexedRectList(BoundsSink<? super T> sink) {
		this.sink = sink;
		this.indexedModCount = modCount;
	}

	@Override
	public boolean add(T member) {
		boolean inSync = indexedModCount == modCount;
		boolean changed = super.add(member);
		if (inSync) {
			sink.addTo(member, index);
			indexedModCount = modCount;
		}
		return changed;
	}

	@Override
	public T set(int position, T member) {
		indexedModCount = modCount - 1;
		return super.set(position, member);
	}

	/** Same predicate as vanilla {@code ScreenRectangle.intersects} against every member that has bounds. */
	public boolean intersectsAny(int left, int top, int right, int bottom) {
		if (indexedModCount != modCount) {
			rebuild();
		}
		return index.intersectsAny(left, top, right, bottom);
	}

	private void rebuild() {
		index.clear();
		for (int i = 0, n = size(); i < n; i++) {
			sink.addTo(get(i), index);
		}
		indexedModCount = modCount;
	}

	@Override
	public void clear() {
		super.clear();
		index.clear();
		indexedModCount = modCount;
	}

	/** Capacity of the backing rectangle index (used to skip pooling abnormally huge lists). */
	public int indexBackingCapacity() {
		return index.backingRectCapacity();
	}

	/** A shallow copy must not share this list's index. */
	@Override
	public Object clone() {
		return new ArrayList<>(this);
	}
}
