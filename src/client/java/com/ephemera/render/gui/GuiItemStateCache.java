package com.ephemera.render.gui;

import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Arrays;

/**
 * Reuses the resolved GUI render state of an item stack for the rest of the client tick.
 * <p>
 * {@code ItemModelResolver.updateForTopItem} rebuilds an identical model description for every static HUD
 * or inventory stack on every frame. Model properties derive from game-tick state, so the result can only
 * change when the tick counter advances or the stack itself changes. An entry is reused only while the tick
 * counter, level, owner and seed match and the stack still has the same count and component patch;
 * everything is dropped when the tick advances. A stack that its owner is currently using is never cached,
 * so charge, pull and use animations resolve every frame.
 * <p>
 * Lookups allocate nothing and copy nothing: the table is direct-mapped by stack identity, the item of a
 * stack cannot change, and component changes are detected through {@link GuiPatchSnapshot} (a reference
 * comparison against a copy-on-write-armed patch). Reusing the same state instance also keeps its model
 * identity key (and the key's cached hash) alive across frames.
 */
public final class GuiItemStateCache {
	private static final int SLOTS = 512;
	private static final int MASK = SLOTS - 1;

	private static final Entry[] TABLE = new Entry[SLOTS];
	private static long tableTick = Long.MIN_VALUE;

	private GuiItemStateCache() {}

	public static TrackingItemStackRenderState find(ItemStack stack, Level level, LivingEntity owner, int seed) {
		if (tableTick != GuiItemBakingManager.textureTicks()) {
			return null;
		}
		Entry entry = TABLE[System.identityHashCode(stack) & MASK];
		if (entry == null || entry.stack != stack || entry.level != level || entry.owner != owner
			|| entry.seed != seed || entry.count != stack.getCount()) {
			return null;
		}
		if (!(stack.getComponents() instanceof GuiPatchSnapshot components)
			|| components.ephemera$patchToken() != entry.patch
			|| isBeingUsed(owner, stack)) {
			return null;
		}
		return entry.state;
	}

	public static void store(ItemStack stack, Level level, LivingEntity owner, int seed, TrackingItemStackRenderState state) {
		if (!(stack.getComponents() instanceof GuiPatchSnapshot components) || isBeingUsed(owner, stack)) {
			return;
		}
		long tick = GuiItemBakingManager.textureTicks();
		if (tableTick != tick) {
			Arrays.fill(TABLE, null);
			tableTick = tick;
		}
		TABLE[System.identityHashCode(stack) & MASK] =
			new Entry(stack, level, owner, seed, stack.getCount(), components.ephemera$armPatchToken(), state);
	}

	public static void clear() {
		Arrays.fill(TABLE, null);
		tableTick = Long.MIN_VALUE;
	}

	/** Use animations (bow draw, eating, shield raise) advance with the owner, not with the stack. */
	private static boolean isBeingUsed(LivingEntity owner, ItemStack stack) {
		return owner != null && owner.isUsingItem() && owner.getUseItem().getItem() == stack.getItem();
	}

	private record Entry(ItemStack stack, Level level, LivingEntity owner, int seed, int count, Object patch,
						 TrackingItemStackRenderState state) {}
}
