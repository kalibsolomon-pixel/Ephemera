package com.ephemera.visuals;

import com.ephemera.core.visuals.DynamicLightGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.IdentityHashMap;

/**
 * Entity-space dynamic lights. Emitters (held light items, burning mobs, spectral arrows, glow
 * squids, dropped glow items) are gathered into a spatial grid at a configurable cadence and
 * folded into the light coords of entities, items, and first-person hands as they render.
 * Terrain lighting is never touched, so there are no chunk rebuilds.
 *
 * <p>Render-thread only.
 */
public final class DynamicLightTracker {
	private static final DynamicLightGrid GRID = new DynamicLightGrid(256);
	private static final IdentityHashMap<Item, Integer> ITEM_LIGHT = new IdentityHashMap<>();
	private static final long MIN_REBUILD_NS = 1_000_000L;

	private static ClientLevel lastLevel;
	private static long lastRebuildNs;
	private static boolean frameTicked;
	private static boolean frameActiveWithEmitters;

	private DynamicLightTracker() {}

	/** Refreshes dynamic light emitters at most once per configured interval; called once per frame. */
	public static void tickFrame(ClientLevel level, long nowNs) {
		frameTicked = true;
		if (level == null || !VisualsRuntime.dynamicLights()) {
			frameActiveWithEmitters = false;
			return;
		}
		refreshAt(level, nowNs);
		frameActiveWithEmitters = !GRID.isEmpty();
	}

	/** Returns {@code packedLight} raised to the dynamic light level at the entity, if brighter. */
	public static int apply(Entity entity, int packedLight) {
		if (frameTicked) {
			if (!frameActiveWithEmitters) {
				return packedLight;
			}
		} else {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (level == null || !VisualsRuntime.dynamicLights()) {
				return packedLight;
			}
			refreshAt(level, System.nanoTime());
			if (GRID.isEmpty()) {
				return packedLight;
			}
		}
		float lit = GRID.lightAt(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
		if (lit <= 0.0f) {
			return packedLight;
		}
		int dynamic = Math.min(240, Math.round(lit * 16.0f));
		if (dynamic <= LightCoordsUtil.smoothBlock(packedLight)) {
			return packedLight;
		}
		return LightCoordsUtil.smoothPack(dynamic, LightCoordsUtil.smoothSky(packedLight));
	}

	public static int emitterCount() {
		return GRID.size();
	}

	/** Releases the pinned level reference and spatial grid on world unload. */
	public static void clear() {
		lastLevel = null;
		lastRebuildNs = 0L;
		frameTicked = false;
		frameActiveWithEmitters = false;
		GRID.clear();
	}

	private static void refreshAt(ClientLevel level, long now) {
		long interval = Math.max(MIN_REBUILD_NS, VisualsRuntime.cfg().visualDynamicLightsUpdateIntervalMs * 1_000_000L);
		if (level == lastLevel && now - lastRebuildNs < interval) {
			return;
		}
		lastLevel = level;
		lastRebuildNs = now;
		GRID.clear();
		for (Entity entity : level.entitiesForRendering()) {
			int emission = emission(entity);
			if (emission > 0) {
				GRID.add(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), emission);
			}
		}
	}

	private static int emission(Entity entity) {
		if (entity.isSpectator()) {
			return 0;
		}
		int best = entity.isOnFire() ? 12 : 0;
		EntityType<?> type = entity.getType();
		if (type == EntityTypes.SPECTRAL_ARROW) {
			best = Math.max(best, 8);
		} else if (type == EntityTypes.GLOW_SQUID) {
			best = Math.max(best, 10);
		} else if (type == EntityTypes.BLAZE) {
			best = Math.max(best, 12);
		} else if (type == EntityTypes.MAGMA_CUBE) {
			best = Math.max(best, 8);
		} else if (type == EntityTypes.FIREBALL || type == EntityTypes.SMALL_FIREBALL) {
			best = Math.max(best, 13);
		}
		if (entity instanceof ItemEntity dropped) {
			best = Math.max(best, itemLight(dropped.getItem()) - 1);
		} else if (entity instanceof LivingEntity living) {
			best = Math.max(best, Math.max(itemLight(living.getMainHandItem()), itemLight(living.getOffhandItem())));
		}
		return best;
	}

	static int itemLight(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		Item item = stack.getItem();
		Integer cached = ITEM_LIGHT.get(item);
		if (cached != null) {
			return cached;
		}
		int level = 0;
		if (item == Items.LAVA_BUCKET) {
			level = 15;
		} else if (item == Items.BLAZE_ROD) {
			level = 10;
		} else if (item instanceof BlockItem blockItem) {
			level = blockItem.getBlock().defaultBlockState().getLightEmission();
		}
		ITEM_LIGHT.put(item, level);
		return level;
	}
}
