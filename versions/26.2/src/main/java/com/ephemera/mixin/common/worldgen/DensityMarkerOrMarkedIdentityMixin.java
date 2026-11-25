package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 */
@Mixin(DensityFunctions.MarkerOrMarked.class)
public abstract class DensityMarkerOrMarkedIdentityMixin {
	@Shadow
	public abstract DensityFunction wrapped();

	@Unique
	private static final Class<?> EPHEMERA$MARKER_CLASS = resolveMarkerClass();

	@Unique
	private static Class<?> resolveMarkerClass() {
		try {
			return Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$Marker");
		} catch (Throwable t) {
			return null;
		}
	}

	@Inject(method = "mapChildren", at = @At("HEAD"), cancellable = true)
	private void ephemera$identityShortCircuit(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
		if (!EphemeraCommon.config().engineFastWorldgenNoise
			|| EPHEMERA$MARKER_CLASS == null || !EPHEMERA$MARKER_CLASS.isInstance(this)) {
			return;
		}
		DensityFunction wrapped = this.wrapped();
		if (visitor.apply(wrapped) == wrapped) {
			cir.setReturnValue((DensityFunction) (Object) this);
		}
	}
}
