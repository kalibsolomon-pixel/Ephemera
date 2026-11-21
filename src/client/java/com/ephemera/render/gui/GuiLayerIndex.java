package com.ephemera.render.gui;

import com.ephemera.core.gui.GuiBoundsIndex;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ScreenArea;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Per-frame spatial index over the append-only element lists of a {@code GuiRenderState} layer node.
 * Each list gets a {@link GuiBoundsIndex} that is extended lazily as the list grows, so repeated
 * "does anything in this layer overlap this rectangle" queries stop walking the list and re-reading
 * {@code ScreenRectangle} records. Answers are identical to the vanilla linear scan.
 */
public final class GuiLayerIndex {
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
