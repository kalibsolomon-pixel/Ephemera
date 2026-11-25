package com.ephemera.render.item;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Duck interface on {@code CuboidItemModelWrapper} for per-display-context cached model AABBs. */
public interface CuboidAabbCacheOwner {
	@Nullable AABB ephemera$getCachedAabb(ItemDisplayContext context);

	void ephemera$putCachedAabb(ItemDisplayContext context, AABB aabb);
}
