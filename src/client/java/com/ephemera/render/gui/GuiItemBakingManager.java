package com.ephemera.render.gui;

import com.ephemera.mixin.client.gui.ItemStackRenderStateAccessor;
import com.ephemera.mixin.client.gui.LayerRenderStateAccessor;
import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;

/**
 * Decides when an animated GUI item must be redrawn into {@code GuiItemAtlas}.
 * <p>
 * Vanilla discards the atlas slot of every item flagged animated at the end of each frame, so sprite
 * animated items (and enchantment glint items) are re-rendered every single frame. Sprite animation
 * only advances in {@code TextureManager.tick()}, so such items keep their slot and are redrawn when the
 * tick counter changes. Glint items animate with real time; they keep vanilla's every-frame redraw unless
 * glint pacing is enabled, in which case they are redrawn once per {@code 1 / guiGlintFps} seconds.
 */
public final class GuiItemBakingManager {
	private static long textureTicks;

	private GuiItemBakingManager() {}

	public static void onTextureTick() {
		textureTicks++;
	}

	public static long textureTicks() {
		return textureTicks;
	}

	@FunctionalInterface
	public interface SlotLookup {
		DynamicAtlasAllocator.Slot lookup(boolean discardAfterFrame);
	}

	public static DynamicAtlasAllocator.Slot resolveSlot(boolean animated, TrackingItemStackRenderState state, SlotLookup lookup) {
		GuiFeatures features = GuiFeatures.get();
		if (!animated || !(features.animatedItemBaking || features.glintRateLimit)) {
			return lookup.lookup(animated);
		}
		boolean foil = hasFoil(state);
		if (foil ? !features.glintRateLimit : !features.animatedItemBaking) {
			return lookup.lookup(true);
		}
		DynamicAtlasAllocator.Slot slot = lookup.lookup(false);
		if (slot instanceof GuiAtlasSlotState slotState) {
			long version = contentVersion(foil, features);
			if (slotState.ephemera$contentVersion() != version) {
				if (slot.state() == DynamicAtlasAllocator.SlotState.READY) {
					slotState.ephemera$markStale();
				}
				slotState.ephemera$setContentVersion(version);
			}
		}
		return slot;
	}

	private static long contentVersion(boolean foil, GuiFeatures features) {
		if (!foil) {
			return textureTicks;
		}
		return (System.nanoTime() / features.glintPeriodNanos) * 0x9E3779B97F4A7C15L + textureTicks;
	}

	private static boolean hasFoil(ItemStackRenderState state) {
		ItemStackRenderStateAccessor accessor = (ItemStackRenderStateAccessor) state;
		ItemStackRenderState.LayerRenderState[] layers = accessor.ephemera$layers();
		int active = accessor.ephemera$activeLayerCount();
		for (int i = 0; i < active; i++) {
			if (((LayerRenderStateAccessor) layers[i]).ephemera$foilType() != ItemStackRenderState.FoilType.NONE) {
				return true;
			}
		}
		return false;
	}
}
