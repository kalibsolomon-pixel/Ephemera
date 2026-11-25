package com.ephemera.render.gui;

import com.ephemera.mixin.client.gui.ItemStackRenderStateAccessor;
import com.ephemera.mixin.client.gui.LayerRenderStateAccessor;
import net.minecraft.client.Minecraft;
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
 * glint pacing is enabled, in which case visible, moving glint advances at {@code guiGlintFps}.
 */
public final class GuiItemBakingManager {
	private static final int MAX_ANIMATED_REBAKES_PER_FRAME = 2;
	private static long textureTicks;
	private static int animatedRebakesThisFrame;

	private GuiItemBakingManager() {}

	/** Resets the per-frame animated/foil atlas rebake budget at frame head. */
	public static void beginFrame() {
		animatedRebakesThisFrame = 0;
	}

	public static void onTextureTick() {
		textureTicks++;
	}

	public static long textureTicks() {
		return textureTicks;
	}

	/** Vanilla behavior: the slot is discarded after the frame exactly when the state is animated. */
	public static final int PLAN_VANILLA = 0;
	/** Animated, but this kind of animation is not paced: discard after the frame like vanilla. */
	public static final int PLAN_DISCARD = 1;
	/** Sprite-animated: keep the slot, redraw once per texture tick. */
	public static final int PLAN_KEEP = 2;
	/** Glint: keep the slot, redraw once per glint period. */
	public static final int PLAN_KEEP_FOIL = 3;

	/** Decides how the slot of {@code state} is handled; see {@link #discardAfterFrame} and {@link #afterLookup}. */
	public static int plan(boolean animated, TrackingItemStackRenderState state) {
		GuiFeatures features = GuiFeatures.get();
		if (!animated || !(features.animatedItemBaking || features.glintRateLimit)) {
			return PLAN_VANILLA;
		}
		if (hasFoil(state)) {
			return features.glintRateLimit ? PLAN_KEEP_FOIL : PLAN_DISCARD;
		}
		return features.animatedItemBaking ? PLAN_KEEP : PLAN_DISCARD;
	}

	/** The flag to pass to {@code DynamicAtlasAllocator.getOrAllocate} for this plan. */
	public static boolean discardAfterFrame(int plan, boolean animated) {
		return animated && plan != PLAN_KEEP && plan != PLAN_KEEP_FOIL;
	}

	/** Marks a kept slot stale when the content version it was drawn at is out of date. */
	public static void afterLookup(int plan, DynamicAtlasAllocator.Slot slot) {
		if ((plan != PLAN_KEEP && plan != PLAN_KEEP_FOIL) || !(slot instanceof GuiAtlasSlotState slotState)) {
			return;
		}
		long version = contentVersion(plan == PLAN_KEEP_FOIL, GuiFeatures.get());
		if (slotState.ephemera$contentVersion() != version) {
			if (slot.state() == DynamicAtlasAllocator.SlotState.READY) {
				// Spread periodic animated/foil slot refreshes across frames (2 per frame) so a
				// full enchanted hotbar + armor set never rebakes all 8+ slots on the exact same
				// frame. New slots (state != READY) always bake immediately.
				if (animatedRebakesThisFrame >= MAX_ANIMATED_REBAKES_PER_FRAME) {
					return;
				}
				animatedRebakesThisFrame++;
				slotState.ephemera$markStale();
			}
			slotState.ephemera$setContentVersion(version);
		}
	}

	private static long contentVersion(boolean foil, GuiFeatures features) {
		if (!foil) {
			return textureTicks;
		}
		var options = Minecraft.getInstance().options;
		double strength = options.glintStrength().get();
		double speed = options.glintSpeed().get();
		long glintStep = strength > 0.0 && speed > 0.0 ? System.nanoTime() / features.glintPeriodNanos : 0;
		// Sprite animations on foil items still follow texture ticks, even with glint turned off.
		return glintStep * 0x9E3779B97F4A7C15L + textureTicks
			^ Double.doubleToLongBits(strength) ^ Long.rotateLeft(Double.doubleToLongBits(speed), 23);
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
