package com.ephemera.mixin.common.worldgen;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public interface NoiseInterpolatorAccess {
	@Invoker("selectCellYZ") void ephemera$selectCellYZ(int cellY, int cellZ);
	@Invoker("updateForY") void ephemera$updateForY(double delta);
	@Invoker("updateForX") void ephemera$updateForX(double delta);
	@Invoker("updateForZ") void ephemera$updateForZ(double delta);
	@Invoker("swapSlices") void ephemera$swapSlices();
}
