package com.ephemera.render.gui;

import com.ephemera.core.gui.GuiBoundsIndex;
import com.ephemera.core.gui.IndexedRectList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ScreenArea;

import java.util.ArrayList;
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
	private static final int MAX_POOLED_LISTS = 64;
	private static final int MAX_POOLED_RECT_CAPACITY = 256;

	// Render-thread frame-scoped pool for IndexedRectList instances created by GuiRenderState$Node.
	// Eliminates ~70 MB of per-frame GuiBoundsIndex int[] + ArrayList Object[] churn.
	private static final ArrayList<IndexedRectList<ScreenArea>> FREE_LISTS = new ArrayList<>(MAX_POOLED_LISTS);
	private static final ArrayList<IndexedRectList<ScreenArea>> IN_USE_LISTS = new ArrayList<>(MAX_POOLED_LISTS);
	private static boolean framePoolReady;

	public static IndexedRectList<ScreenArea> borrowList() {
		Minecraft mc = Minecraft.getInstance();
		if (!framePoolReady || mc == null || !mc.isSameThread()) {
			return new IndexedRectList<>(AREA_BOUNDS);
		}
		IndexedRectList<ScreenArea> list;
		int freeCount = FREE_LISTS.size();
		if (freeCount > 0) {
			list = FREE_LISTS.remove(freeCount - 1);
		} else {
			list = new IndexedRectList<>(AREA_BOUNDS);
		}
		if (IN_USE_LISTS.size() < MAX_POOLED_LISTS) {
			IN_USE_LISTS.add(list);
		}
		return list;
	}

	/** Called at {@code GuiRenderState.reset()} HEAD to recycle previous frame's layer lists. */
	public static void recycleFrameLists() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || !mc.isSameThread()) {
			return;
		}
		framePoolReady = true;
		for (int i = IN_USE_LISTS.size() - 1; i >= 0; i--) {
			IndexedRectList<ScreenArea> list = IN_USE_LISTS.remove(i);
			list.clear();
			if (FREE_LISTS.size() < MAX_POOLED_LISTS && list.indexBackingCapacity() <= MAX_POOLED_RECT_CAPACITY) {
				FREE_LISTS.add(list);
			}
		}
	}

	private final IdentityHashMap<List<?>, Entry> lists = new IdentityHashMap<>();

	public void clear() {
		lists.clear();
		recycleFrameLists();
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
