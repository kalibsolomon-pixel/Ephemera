package com.ephemera.api;

/**
 * Declares which optimization and visual subsystems are currently active in Ephemera.
 * Aurora Client reads this so it can yield overlapping hooks and share Ephemera's
 * visibility, occlusion, shader, and frame-pacing services cleanly.
 */
public record EphemeraCapabilities(
	boolean terrainEngineActive,
	boolean entityOcclusionActive,
	boolean blockEntityOcclusionActive,
	boolean guiOptimizationsActive,
	boolean textOptimizationsActive,
	boolean particleOptimizationsActive,
	boolean backendDispatchCacheActive,
	boolean lightingEngineActive,
	boolean smoothFogActive,
	boolean gradientDitherActive,
	boolean dynamicLightsActive,
	String graphicsBackend
) {}
