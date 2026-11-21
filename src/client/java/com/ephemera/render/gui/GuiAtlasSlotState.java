package com.ephemera.render.gui;

/**
 * Extra per-slot state mixed into {@code DynamicAtlasAllocator.Slot}: the content version the slot was
 * last drawn at, and a way to force the next lookup to redraw it.
 */
public interface GuiAtlasSlotState {
	long ephemera$contentVersion();

	void ephemera$setContentVersion(long version);

	/** Marks a ready slot as stale so {@code GuiItemAtlas} redraws it on the current lookup. */
	void ephemera$markStale();
}
