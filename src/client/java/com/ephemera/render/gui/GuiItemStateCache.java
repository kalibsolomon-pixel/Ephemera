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
 * Lookups allocate nothing and copy nothing: the table is 2-way set-associative by stack identity (the
 * creative inventory's ~75 stacks produce ~5-6 birthday-paradox collision pairs per frame at 1-way
 * 512 slots, evicting each other in a cycle), the item of a stack cannot change, and component changes are
 * detected through {@link GuiPatchSnapshot} (a reference comparison against a copy-on-write-armed patch).
 * Reusing the same state instance also keeps its model identity key (and the key's cached hash) alive
 * across frames.
 */
public final class GuiItemStateCache {
	private static final int SETS = 512;
	private static final int SET_MASK = SETS - 1;
	private static final int WAYS = 2;

	private static final Entry[] TABLE = new Entry[SETS * WAYS];
	private static long tableTick = Long.MIN_VALUE;
	/** Round-robin store way per set, flipped on each overwrite of an occupied pair. */
	private static long storeCount;

	private GuiItemStateCache() {}

	public static TrackingItemStackRenderState find(ItemStack stack, Level level, LivingEntity owner, int seed) {
		if (tableTick != GuiItemBakingManager.textureTicks()) {
			return null;
		}
		int set = System.identityHashCode(stack) & SET_MASK;
		Entry entry = TABLE[set];
		if (entry == null || !matches(entry, stack, level, owner, seed)) {
			entry = TABLE[SETS + set];
			if (entry == null || !matches(entry, stack, level, owner, seed)) {
				return null;
			}
		}
		if (!(stack.getComponents() instanceof GuiPatchSnapshot components)
			|| components.ephemera$patchToken() != entry.patch
			|| isBeingUsed(owner, stack)) {
			return null;
		}
		return entry.state;
	}

	private static boolean matches(Entry entry, ItemStack stack, Level level, LivingEntity owner, int seed) {
		return entry.stack == stack && entry.level == level && entry.owner == owner
			&& entry.seed == seed && entry.count == stack.getCount();
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
		Entry entry = new Entry(stack, level, owner, seed, stack.getCount(), components.ephemera$armPatchToken(), state);
		int set = System.identityHashCode(stack) & SET_MASK;
		int way;
		if (TABLE[set] == null) {
			way = 0;
		} else if (TABLE[SETS + set] == null) {
			way = 1;
		} else {
			// Both ways occupied: flip-flop the victim so cycling stacks each keep one way warm.
			way = ((int) (storeCount++ & 1L)) == 0 ? 1 : 0;
		}
		TABLE[way * SETS + set] = entry;
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
