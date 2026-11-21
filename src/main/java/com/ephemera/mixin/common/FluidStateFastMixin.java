package com.ephemera.mixin.common;

import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caches immutable {@code Fluid.isEmpty()} on {@link FluidState} at construction time (and lazily
 * caches {@code isRandomlyTicking()} via a tri-state byte for full modded-fluid safety), replacing
 * virtual {@code getType().isEmpty()} dispatch with a 1-cycle field read across worldgen, meshing,
 * lighting, and fluid ticking.
 */
@Mixin(FluidState.class)
abstract class FluidStateFastMixin {
	@Shadow public abstract Fluid getType();

	@Unique private boolean ephemera$empty;
	@Unique private byte ephemera$randomTickState; // 0 = unknown, 1 = true, -1 = false

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$cacheEmpty(Fluid owner, Property<?>[] properties, Comparable<?>[] values, CallbackInfo ci) {
		this.ephemera$empty = ((FluidAccess) owner).ephemera$isEmpty();
	}

	/**
	 * @author Ephemera
	 * @reason Direct cached boolean load instead of checkcast + virtual Fluid.isEmpty() dispatch.
	 */
	@Overwrite
	public boolean isEmpty() {
		return this.ephemera$empty;
	}

	/**
	 * @author Ephemera
	 * @reason Cached tri-state field load for FluidState.isRandomlyTicking().
	 */
	@Overwrite
	public boolean isRandomlyTicking() {
		byte s = this.ephemera$randomTickState;
		if (s != 0) {
			return s > 0;
		}
		boolean ticking = ((FluidAccess) this.getType()).ephemera$isRandomlyTicking();
		this.ephemera$randomTickState = (byte) (ticking ? 1 : -1);
		return ticking;
	}
}
