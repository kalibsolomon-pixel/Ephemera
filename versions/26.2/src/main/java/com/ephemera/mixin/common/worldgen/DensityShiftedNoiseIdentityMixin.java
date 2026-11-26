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
 * Single-visit identity for vanilla {@code mapChildren}: all three shift children and the noise
 * holder map to themselves. Vanilla re-walks the entire noise-router graph 8x per generated
 * chunk (NoiseRouter + 6 climate sampler parameters + finalDensity), rebuilding every immutable
 * node per walk even when all mapped children are identity-equal.
 *
 * <p>Applies the visitor exactly once per child and never falls through: identity returns
 * {@code this}, otherwise reconstructs the exact node vanilla's body builds —
 * {@code new ShiftedNoise(apply(shiftX), apply(shiftY), apply(shiftZ), xzScale, yScale,
 * visitNoise(noise))} — through {@link DensityGraphReconstruction} ({@code ShiftedNoise} is a
 * protected nested class). A HEAD pre-apply that falls through is what made the wave-5 identity
 * mixins double-visit every non-identity subtree (O(2^d) in graph depth); single-apply is
 * vanilla semantics, so this is a correctness fix as much as a performance one.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise")
public abstract class DensityShiftedNoiseIdentityMixin {
	@Shadow private DensityFunction shiftX;
	@Shadow private DensityFunction shiftY;
	@Shadow private DensityFunction shiftZ;
	@Shadow private double xzScale;
	@Shadow private double yScale;
	@Shadow private DensityFunction.NoiseHolder noise;

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$singleVisitIdentity(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise || !DensityGraphReconstruction.shiftedNoiseReady()) {
			return;
		}
		DensityFunction mappedX = visitor.apply(this.shiftX);
		DensityFunction mappedY = visitor.apply(this.shiftY);
		DensityFunction mappedZ = visitor.apply(this.shiftZ);
		DensityFunction.NoiseHolder mappedNoise = visitor.visitNoise(this.noise);
		if (mappedX == this.shiftX && mappedY == this.shiftY && mappedZ == this.shiftZ
				&& mappedNoise == this.noise) {
			cir.setReturnValue((DensityFunction) (Object) this);
			return;
		}
		DensityFunction rebuilt = DensityGraphReconstruction.shiftedNoise(
			mappedX, mappedY, mappedZ, this.xzScale, this.yScale, mappedNoise);
		if (rebuilt != null) {
			cir.setReturnValue(rebuilt);
		}
	}
}
