package com.ephemera.api;

/**
 * Shader composition coordination contract so Aurora Client (Glass, Smooth Fog,
 * Gradient Dithering) and shader mods (Iris) never collide on core shaders.
 */
public interface EphemeraShaderHooks {
	boolean isExternalShaderPackActive();

	boolean ownsSmoothFog();

	boolean ownsGradientDithering();

	boolean ownsDynamicLighting();

	/** True when Ephemera may illuminate nearby terrain around the player's held light source. */
	default boolean ownsHeldWorldLight() {
		return false;
	}
}
