package com.ephemera.mixin.common;

import com.ephemera.engine.common.FluidTrackerAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.entity.EntityFluidInteraction$Tracker")
abstract class EntityFluidTrackerMixin implements FluidTrackerAccess {
	@Shadow private double height;
	@Shadow private boolean eyesInside;
	@Shadow public abstract void reset();

	@Override
	public double ephemera$height() {
		return this.height;
	}

	@Override
	public void ephemera$setHeight(double height) {
		this.height = height;
	}

	@Override
	public void ephemera$setEyesInside(boolean eyesInside) {
		this.eyesInside = eyesInside;
	}

	@Override
	public void ephemera$reset() {
		this.reset();
	}
}
