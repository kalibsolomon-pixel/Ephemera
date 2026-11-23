package com.ephemera.mixin.common.worldgen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reuses a single per-{@code NoiseBasedAquifer} {@link MutableDouble} inside {@code computeSubstance}
 * instead of allocating a fresh {@code new MutableDouble(Double.NaN)} for every aquifer block.
 * {@code NoiseBasedAquifer} instances are per-chunk and single-threaded.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
abstract class AquiferFastMixin {
	@Unique
	private final MutableDouble ephemera$barrierNoise = new MutableDouble(Double.NaN);

	@WrapOperation(
		method = "computeSubstance",
		at = @At(value = "NEW", target = "(D)Lorg/apache/commons/lang3/mutable/MutableDouble;")
	)
	private MutableDouble ephemera$reuseBarrierNoise(double initialValue, Operation<MutableDouble> original) {
		MutableDouble reused = this.ephemera$barrierNoise;
		reused.setValue(initialValue);
		return reused;
	}
}
