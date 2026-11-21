package com.ephemera.render.gui;

import net.minecraft.client.gui.render.DynamicAtlasAllocator;

/** Mixed into {@code DynamicAtlasAllocator}: re-checks a remembered slot without probing the key map. */
public interface GuiSlotLookup {
	/**
	 * @return {@code slot}, touched exactly as a {@code getOrAllocate} hit would, if it is still allocated
	 *         and was allocated for a key equal to {@code key}; otherwise null (use the real lookup)
	 */
	DynamicAtlasAllocator.Slot ephemera$revalidate(DynamicAtlasAllocator.Slot slot, Object key, boolean discardAfterFrame);
}
