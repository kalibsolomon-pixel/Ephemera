package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.phys.shapes.CubePointRange;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caches immutable {@link CubePointRange} instances for common grid resolutions (1..16),
 * eliminating per-call allocations in {@link CubeVoxelShape#getCoords} (including {@code Shapes.block()}).
 */
@Mixin(CubeVoxelShape.class)
abstract class CubeVoxelShapeFastMixin {
	@Unique
	private static final CubePointRange[] EPHEMERA$CACHED_RANGES = new CubePointRange[17];

	static {
		for (int i = 1; i < EPHEMERA$CACHED_RANGES.length; i++) {
			EPHEMERA$CACHED_RANGES[i] = new CubePointRange(i);
		}
	}

	@WrapOperation(
		method = "getCoords",
		at = @At(value = "NEW", target = "(I)Lnet/minecraft/world/phys/shapes/CubePointRange;")
	)
	private CubePointRange ephemera$reuseCubePointRange(int parts, Operation<CubePointRange> original) {
		if (EphemeraCommon.config().engineFastCollisions && parts >= 1 && parts < EPHEMERA$CACHED_RANGES.length) {
			return EPHEMERA$CACHED_RANGES[parts];
		}
		return original.call(parts);
	}
}
