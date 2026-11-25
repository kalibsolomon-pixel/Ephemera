package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Identity short-circuit for vanilla {@code mapChildren}: the mapped input and every mapped interval function are identity-equal.
 * Vanilla re-walks the entire noise-router graph 8x per generated chunk (NoiseRouter + 6
 * climate sampler parameters + finalDensity), rebuilding every immutable node per walk even when
 * all mapped children are identity-equal. Returning this on the identity-equal case removes the
 * churn with zero behavior change (nodes are immutable records with constructor-cached bounds).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$IntervalSelect")
public abstract class DensityIntervalSelectIdentityMixin {
	@Shadow private DensityFunction input;
	@Shadow private List<DensityFunction> functions;

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$identityShortCircuit(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise) {
			return;
		}
		if (visitor.apply(this.input) != this.input) {
			return;
		}
		for (DensityFunction function : this.functions) {
			if (visitor.apply(function) != function) {
				return;
			}
		}
		cir.setReturnValue((DensityFunction) (Object) this);
	}
}
