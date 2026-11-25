package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Identity short-circuit for vanilla {@code mapChildren}: the wrapped density value maps to itself and the holder is already direct.
 * Vanilla re-walks the entire noise-router graph 8x per generated chunk (NoiseRouter + 6
 * climate sampler parameters + finalDensity), rebuilding every immutable node per walk even when
 * all mapped children are identity-equal. Returning this on the identity-equal case removes the
 * churn with zero behavior change (nodes are immutable records with constructor-cached bounds).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder")
public abstract class DensityHolderHolderIdentityMixin {
	@Shadow private Holder<DensityFunction> function;

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$identityShortCircuit(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise) {
			return;
		}
		if (!(this.function instanceof Holder.Direct)) {
			return;
		}
		DensityFunction value = this.function.value();
		if (visitor.apply(value) == value) {
			cir.setReturnValue((DensityFunction) (Object) this);
		}
	}
}
