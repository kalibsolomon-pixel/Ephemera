package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions.Spline.Coordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single-visit identity for vanilla {@code mapChildren}: the wrapped density function maps to
 * itself (required for the Spline/Multipoint short-circuits to reach the top of the graph).
 * Vanilla re-walks the entire noise-router graph 8x per generated chunk (NoiseRouter + 6 climate
 * sampler parameters + finalDensity), rebuilding every immutable node per walk even when all
 * mapped children are identity-equal.
 *
 * <p>Applies the visitor exactly once and never falls through: identity returns {@code this},
 * otherwise reconstructs the exact node vanilla's body builds —
 * {@code new Coordinate(visitor.apply(this.function))} (the target's {@code mapChildren} returns
 * {@code Coordinate}, which is a spline coordinate and not a {@code DensityFunction}). A HEAD
 * pre-apply that falls through is what made the wave-5 identity mixins double-visit every
 * non-identity subtree (O(2^d) in graph depth); single-apply is vanilla semantics, so this is a
 * correctness fix as much as a performance one.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate")
public abstract class SplineCoordinateIdentityMixin {
	@Shadow private DensityFunction function;

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$singleVisitIdentity(DensityFunction.Visitor visitor, CallbackInfoReturnable<Coordinate> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise) {
			return;
		}
		DensityFunction mapped = visitor.apply(this.function);
		if (mapped == this.function) {
			cir.setReturnValue((Coordinate) (Object) this);
		} else {
			cir.setReturnValue(new Coordinate(mapped));
		}
	}
}
