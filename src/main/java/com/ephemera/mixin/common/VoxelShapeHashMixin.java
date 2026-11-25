package com.ephemera.mixin.common;

import com.ephemera.engine.ShapeHashCache;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Caches the canonical interning hash on immutable shapes so repeated
 * {@code StateInterning.shape()} probes during block-state cache bootstrap stop re-running the
 * allocating {@code toAabbs()}-based hash on every probe and collision check. Shapes are
 * immutable after construction, so the cache never goes stale.
 */
@Mixin(VoxelShape.class)
abstract class VoxelShapeHashMixin implements ShapeHashCache {
	@Unique
	private int ephemera$shapeHash;

	@Override
	public int ephemera$cachedShapeHash() {
		return ephemera$shapeHash;
	}

	@Override
	public void ephemera$setCachedShapeHash(int hash) {
		ephemera$shapeHash = hash;
	}
}
