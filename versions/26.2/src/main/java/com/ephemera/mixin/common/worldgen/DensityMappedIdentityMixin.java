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
 * Single-visit identity for vanilla {@code mapChildren}. Vanilla re-walks the entire noise-router
 * graph 8x per generated chunk (NoiseRouter + 6 climate sampler parameters + finalDensity),
 * rebuilding every immutable node per walk even when all mapped children are identity-equal.
 *
 * <p>This inject applies the visitor to the input exactly once and <em>never falls through</em>:
 * identity returns {@code this}, otherwise the node vanilla's body would have built is
 * reconstructed as {@code Mapped.create(node.type, mappedInput)} — byte-for-byte the vanilla
 * expression {@code create(this.type, visitor.apply(this.input))}. {@code Mapped} is a protected
 * nested class, so the reconstruction runs through {@link DensityGraphReconstruction}; when that
 * setup fails the handler returns without injecting, which is plain vanilla single-visit
 * behavior (the correct failure mode). Falling through after a HEAD pre-apply is what made the
 * wave-5 identity mixins double-visit every non-identity subtree (O(2^d) in graph depth);
 * single-apply is vanilla semantics, so this is a correctness fix as much as a performance one.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Mapped")
public abstract class DensityMappedIdentityMixin {
	@Shadow private DensityFunction input;

	@Inject(method = "mapChildren(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunctions$Mapped;", at = @At("HEAD"), cancellable = true)
	private void ephemera$singleVisitIdentity(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise || !DensityGraphReconstruction.mappedReady()) {
			return;
		}
		DensityFunction mapped = visitor.apply(this.input);
		if (mapped == this.input) {
			cir.setReturnValue((DensityFunction) (Object) this);
			return;
		}
		DensityFunction rebuilt = DensityGraphReconstruction.mapped(this, mapped);
		if (rebuilt != null) {
			cir.setReturnValue(rebuilt);
		}
	}
}
