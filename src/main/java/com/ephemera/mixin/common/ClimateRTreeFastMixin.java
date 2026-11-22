package com.ephemera.mixin.common;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eliminates the per-lookup {@code new long[7]} allocation inside {@code Climate.RTree.search}
 * by filling a reusable per-thread scratch buffer.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree")
abstract class ClimateRTreeFastMixin {
	@Unique
	private static final ThreadLocal<long[]> EPHEMERA$TARGET_SCRATCH = ThreadLocal.withInitial(() -> new long[7]);

	@WrapOperation(
		method = "search",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Climate$TargetPoint;toParameterArray()[J"
		)
	)
	private long[] ephemera$reuseTargetArray(Climate.TargetPoint target, Operation<long[]> original) {
		long[] params = EPHEMERA$TARGET_SCRATCH.get();
		params[0] = target.temperature();
		params[1] = target.humidity();
		params[2] = target.continentalness();
		params[3] = target.erosion();
		params[4] = target.depth();
		params[5] = target.weirdness();
		params[6] = 0L;
		return params;
	}
}
