package com.ephemera.render.item;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Duck interface on {@code ItemStackRenderState} to attach a single-layer cuboid AABB cache. */
public interface ItemStackAabbAccess {
	void ephemera$attachCuboidAabb(CuboidAabbCacheOwner owner, ItemDisplayContext context, @Nullable AABB cached);
}
