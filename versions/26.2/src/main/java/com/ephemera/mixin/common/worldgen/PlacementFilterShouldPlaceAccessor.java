package com.ephemera.mixin.common.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the protected abstract predicate of {@code PlacementFilter} for fast-path dispatch. */
@Mixin(PlacementFilter.class)
public interface PlacementFilterShouldPlaceAccessor {
	@Invoker("shouldPlace")
	boolean ephemera$shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos);
}
