package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Identity short-circuit for vanilla {@code mapChildren}: the wrapped density value maps to
 * itself. Only concrete {@code DensityFunctions$Marker} instances normalize identity-safely —
 * the NoiseChunk stateful wrapper classes (NoiseInterpolator, FlatCache, Cache2D, CacheOnce,
 * CacheAllInCell, BlendDensity) keep vanilla's Marker normalization so chunk-local interpolation
 * state never leaks between chunks or into foreign visitors. The runtime Marker check is
 * reflective because {@code DensityFunctions$Marker} is not accessible from this package.
 *
 * <p>Interface-shaped mixin: the target is an interface, so a class-shaped mixin fails to apply
 * (target type mismatch at boot).
 */
@Mixin(DensityFunctions.MarkerOrMarked.class)
public interface DensityMarkerOrMarkedIdentityMixin extends DensityFunctions.MarkerOrMarked {
	@Unique
	Class<?> EPHEMERA$MARKER_CLASS = resolveMarkerClass();

	@Unique
	private static Class<?> resolveMarkerClass() {
		try {
			return Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$Marker");
		} catch (Throwable t) {
			return null;
		}
	}

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	default void ephemera$identityShortCircuit(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise
			|| EPHEMERA$MARKER_CLASS == null || !EPHEMERA$MARKER_CLASS.isInstance(this)) {
			return;
		}
		DensityFunction wrapped = this.wrapped();
		if (visitor.apply(wrapped) == wrapped) {
			cir.setReturnValue((DensityFunction) this);
		}
	}
}
