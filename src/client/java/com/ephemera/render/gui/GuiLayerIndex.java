package com.ephemera.render.gui;

import com.ephemera.core.gui.GuiBoundsIndex;
import com.ephemera.core.gui.IndexedRectList;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ScreenArea;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Spatial index over the append-only element lists of a {@code GuiRenderState} layer node, so repeated
 * "does anything in this layer overlap this rectangle" queries stop walking the list and re-reading
 * {@code ScreenRectangle} records. Answers are identical to the vanilla linear scan.
 * <p>
 * Node lists are normally created as {@link IndexedRectList}s (see {@code GuiRenderStateNodeMixin}), which
 * carry their own index and are queried directly via {@link #intersects}. Any other list (a node created
 * before the node mixin applied, or one swapped by another mod) falls back to a per-frame side table
 * with a {@link GuiBoundsIndex} per list that is extended lazily as the list grows.
 */
public final class GuiLayerIndex {
	/** Feeds a list member's bounds into an {@link IndexedRectList}; members without bounds are skipped. */
	public static final IndexedRectList.BoundsSink<ScreenArea> AREA_BOUNDS = (area, index) -> {
		ScreenRectangle bounds = area.bounds();
		if (bounds != null) {
			index.add(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
		}
	};

	private static final int MIN_INDEXED_SIZE = 8;

	private final IdentityHashMap<List<?>, Entry> lists = new IdentityHashMap<>();

	public void clear() {
		lists.clear();
	}

	public boolean intersects(ScreenRectangle query, List<? extends ScreenArea> areas) {
		if (areas == null) {
			return false;
		}
		int left = query.left();
		int top = query.top();
		int right = query.right();
		int bottom = query.bottom();
		if (areas instanceof IndexedRectList<?> indexed) {
			return indexed.intersectsAny(left, top, right, bottom);
		}
		int size = areas.size();
		if (size < MIN_INDEXED_SIZE) {
			for (int i = 0; i < size; i++) {
				ScreenRectangle bounds = areas.get(i).bounds();
				if (bounds != null && bounds.left() < right && bounds.right() > left
					&& bounds.top() < bottom && bounds.bottom() > top) {
					return true;
				}
			}
			return false;
		}
		Entry entry = lists.get(areas);
		if (entry == null) {
			entry = new Entry();
			lists.put(areas, entry);
		} else if (entry.consumed > size) {
			entry.bounds.clear();
			entry.consumed = 0;
		}
		for (int i = entry.consumed; i < size; i++) {
			ScreenRectangle bounds = areas.get(i).bounds();
			if (bounds != null) {
				entry.bounds.add(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
			}
		}
		entry.consumed = size;
		return entry.bounds.intersectsAny(left, top, right, bottom);
	}

	private static final class Entry {
		final GuiBoundsIndex bounds = new GuiBoundsIndex();
		int consumed;
	}
}
