package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.worldgen.DensityGraphReconstruction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single-visit identity for vanilla {@code mapChildren}: the mapped input and every mapped
 * interval function are identity-equal. Vanilla re-walks the entire noise-router graph 8x per
 * generated chunk (NoiseRouter + 6 climate sampler parameters + finalDensity), rebuilding every
 * immutable node per walk even when all mapped children are identity-equal.
 *
 * <p>Applies the visitor exactly once per child and never falls through: identity returns
 * {@code this}, otherwise reconstructs the exact node vanilla's body builds —
 * {@code new IntervalSelect(apply(input), thresholds, copyOf(transform(functions, apply)))}
 * with the mapped functions gathered into an eagerly-copied list, matching vanilla — through
 * {@link DensityGraphReconstruction} (the type is package-private with a private constructor).
 * The identity path stays zero-alloc; the eager mapping tail only runs when a child is not
 * identity-equal. A HEAD pre-apply that falls through is what made the wave-5 identity mixins
 * double-visit every non-identity subtree (O(2^d) in graph depth); single-apply is vanilla
 * semantics, so this is a correctness fix as much as a performance one.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$IntervalSelect")
public abstract class DensityIntervalSelectIdentityMixin {
	@Shadow private DensityFunction input;
	@Shadow private it.unimi.dsi.fastutil.doubles.DoubleList thresholds;
	@Shadow private List<DensityFunction> functions;

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$singleVisitIdentity(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise || !DensityGraphReconstruction.intervalSelectReady()) {
			return;
		}
		DensityFunction mappedInput = visitor.apply(this.input);
		List<DensityFunction> mappedFunctions = null;
		int size = this.functions.size();
		for (int i = 0; i < size; i++) {
			DensityFunction function = this.functions.get(i);
			DensityFunction mapped = visitor.apply(function);
			if (mapped != function) {
				// First non-identity child: keep the already-mapped prefix, apply the tail once.
				mappedFunctions = new ArrayList<>(size);
				for (int j = 0; j < i; j++) {
					mappedFunctions.add(this.functions.get(j));
				}
				mappedFunctions.add(mapped);
				for (int j = i + 1; j < size; j++) {
					mappedFunctions.add(visitor.apply(this.functions.get(j)));
				}
				break;
			}
		}
		if (mappedInput == this.input && mappedFunctions == null) {
			cir.setReturnValue((DensityFunction) (Object) this);
			return;
		}
		DensityFunction rebuilt = DensityGraphReconstruction.intervalSelect(
			mappedInput,
			this.thresholds,
			mappedFunctions != null ? List.copyOf(mappedFunctions) : this.functions);
		if (rebuilt != null) {
			cir.setReturnValue(rebuilt);
		}
	}
}
