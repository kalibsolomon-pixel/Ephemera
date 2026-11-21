package com.ephemera.render.gui;

import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;

/**
 * Reuses the resolved GUI render state of an item stack for the rest of the client tick.
 * <p>
 * {@code ItemModelResolver.updateForTopItem} rebuilds an identical model description for every static HUD
 * or inventory stack on every frame. Model properties derive from game-tick state, so the result can only
 * change when the tick counter advances or the stack itself changes. An entry is reused only while the tick
 * counter, level, owner and seed match and a snapshot of the stack has the same item and components;
 * everything is dropped when the tick advances.
 */
public final class GuiItemStateCache {
	private static final int MAX_ENTRIES = 1024;

	private static final IdentityHashMap<ItemStack, Entry> ENTRIES = new IdentityHashMap<>();
	private static long entriesTick = Long.MIN_VALUE;

	private GuiItemStateCache() {}

	public static TrackingItemStackRenderState find(ItemStack stack, Level level, LivingEntity owner, int seed) {
		if (entriesTick != GuiItemBakingManager.textureTicks()) {
			return null;
		}
		Entry entry = ENTRIES.get(stack);
		if (entry != null && entry.level == level && entry.owner == owner && entry.seed == seed
			&& ItemStack.isSameItemSameComponents(entry.snapshot, stack)) {
			return entry.state;
		}
		return null;
	}

	public static void store(ItemStack stack, Level level, LivingEntity owner, int seed, TrackingItemStackRenderState state) {
		long tick = GuiItemBakingManager.textureTicks();
		if (entriesTick != tick || ENTRIES.size() >= MAX_ENTRIES) {
			ENTRIES.clear();
			entriesTick = tick;
		}
		ENTRIES.put(stack, new Entry(stack.copy(), level, owner, seed, state));
	}

	private record Entry(ItemStack snapshot, Level level, LivingEntity owner, int seed, TrackingItemStackRenderState state) {}
}
