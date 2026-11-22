package com.ephemera.render.terrain;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ColorResolver;

/**
 * Duck interface binding a {@code BlockTintCache} to its owning {@link ClientLevel} and
 * {@link ColorResolver} so cache misses can populate an entire 16x16 Y-slice in O(1) per block
 * via {@link com.ephemera.core.terrain.BiomePrefixBlender}.
 */
public interface BlockTintCachePrefixHook {
	void ephemera$bind(ClientLevel level, ColorResolver resolver);
}
