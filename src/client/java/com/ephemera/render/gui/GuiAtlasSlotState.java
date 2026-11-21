package com.ephemera.render.gui;

/**
 * Extra per-slot state mixed into {@code DynamicAtlasAllocator.Slot}: the content version the slot was
 * last drawn at, a way to force the next lookup to redraw it, and the key the slot was allocated for.
 */
public interface GuiAtlasSlotState {
	long ephemera$contentVersion();

	void ephemera$setContentVersion(long version);

	/** Marks a ready slot as stale so {@code GuiItemAtlas} redraws it on the current lookup. */
	void ephemera$markStale();

	/** The key the slot was last allocated for (stamped by {@code DynamicAtlasAllocator.getOrAllocate}). */
	Object ephemera$owner();

	void ephemera$setOwner(Object key);

	/** What a {@code getOrAllocate} hit does to a slot: keep it past the frame if animated, mark it ready. */
	void ephemera$touch(boolean discardAfterFrame);
}
