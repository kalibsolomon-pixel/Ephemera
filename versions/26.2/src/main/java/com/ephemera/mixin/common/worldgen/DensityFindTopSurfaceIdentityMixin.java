package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.worldgen.DensityGraphReconstruction;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single-visit identity for vanilla {@code mapChildren}: both mapped children are identity-equal.
 * Vanilla re-walks the entire noise-router graph 8x per generated chunk (NoiseRouter + 6 climate
 * sampler parameters + finalDensity), rebuilding every immutable node per walk even when all
 * mapped children are identity-equal.
 *
 * <p>Applies the visitor exactly once per child and never falls through: identity returns
 * {@code this}, otherwise reconstructs the exact node vanilla's body builds —
 * {@code new FindTopSurface(apply(density), apply(upperBound), lowerBound, cellHeight)} —
 * through {@link DensityGraphReconstruction} (the type is package-private with a private
 * constructor). A HEAD pre-apply that falls through is what made the wave-5 identity mixins
 * double-visit every non-identity subtree (O(2^d) in graph depth); single-apply is vanilla
 * semantics, so this is a correctness fix as much as a performance one.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$FindTopSurface")
public abstract class DensityFindTopSurfaceIdentityMixin {
	@Shadow private DensityFunction density;
	@Shadow private DensityFunction upperBound;
	@Shadow private int lowerBound;
	@Shadow private int cellHeight;

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$singleVisitIdentity(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise || !DensityGraphReconstruction.findTopSurfaceReady()) {
			return;
		}
		DensityFunction mappedDensity = visitor.apply(this.density);
		DensityFunction mappedUpper = visitor.apply(this.upperBound);
		if (mappedDensity == this.density && mappedUpper == this.upperBound) {
			cir.setReturnValue((DensityFunction) (Object) this);
			return;
		}
		DensityFunction rebuilt = DensityGraphReconstruction.findTopSurface(
			mappedDensity, mappedUpper, this.lowerBound, this.cellHeight);
		if (rebuilt != null) {
			cir.setReturnValue(rebuilt);
		}
	}
}
