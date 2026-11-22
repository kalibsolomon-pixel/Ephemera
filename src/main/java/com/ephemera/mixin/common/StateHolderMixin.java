package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.StateInterning;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Deduplicates immutable {@code propertyValues} arrays across {@link StateHolder} instances that
 * share identical property-value tuples (e.g. all stairs, slabs, fences, walls, and waterlogged
 * blocks across vanilla and modded blocks).
 */
@Mixin(StateHolder.class)
abstract class StateHolderMixin {
	@Shadow @Final @Mutable private Comparable<?>[] propertyValues;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$internPropertyValues(Object owner, Property<?>[] keys, Comparable<?>[] values, CallbackInfo ci) {
		if (EphemeraCommon.config().memoryCompactBlockStates && propertyValues != null && propertyValues.length > 0) {
			propertyValues = StateInterning.propertyValues(propertyValues);
		}
	}
}
