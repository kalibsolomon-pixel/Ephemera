package com.ephemera.engine.common;

/**
 * Direct accessor interface mixed into {@code EntityFluidInteraction$Tracker} so
 * {@code EntityFluidInteractionFastMixin} can update {@code height} and {@code eyesInside}
 * without reflection or extra wrapper allocations.
 */
public interface FluidTrackerAccess {
	double ephemera$height();

	void ephemera$setHeight(double height);

	void ephemera$setEyesInside(boolean eyesInside);

	void ephemera$reset();
}
