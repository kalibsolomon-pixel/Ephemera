package com.ephemera.core.visuals;

/**
 * Pure-Java math for Ephemera's single interpolated held-item terrain light.
 *
 * <p>Keeps smoothing, radius scaling, wall-safe origin fallback, and shader uniform packing testable
 * without Minecraft runtime dependencies.
 */
public final class HeldWorldLightMath {
	public static final float DEFAULT_RADIUS = 8.5f;
	public static final float MIN_RADIUS = 4.0f;
	public static final float MAX_RADIUS = 12.0f;
	public static final float DEFAULT_STRENGTH = 0.85f;
	public static final float MIN_STRENGTH = 0.25f;
	public static final float MAX_STRENGTH = 1.0f;
	/** Exponential smoothing time constant (~65 ms) for held-item hotbar switches. */
	private static final float FADE_TAU_SECONDS = 0.065f;
	private static final float EPSILON = 0.01f;

	private HeldWorldLightMath() {}

	public static float clampRadius(float radius) {
		if (!Float.isFinite(radius) || radius <= 0.0f) {
			return DEFAULT_RADIUS;
		}
		return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
	}

	public static float clampStrength(float strength) {
		if (!Float.isFinite(strength) || strength <= 0.0f) {
			return DEFAULT_STRENGTH;
		}
		return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, strength));
	}

	/**
	 * Smoothly approaches {@code targetLevel} (0..15) using frame-rate-independent exponential
	 * damping so switching to or away from a torch glides instead of snapping.
	 */
	public static float smoothLevel(float currentLevel, float targetLevel, float dtSeconds) {
		float clampedTarget = Math.max(0.0f, Math.min(15.0f, targetLevel));
		float clampedCurrent = Math.max(0.0f, Math.min(15.0f, currentLevel));
		if (!Float.isFinite(dtSeconds) || dtSeconds <= 0.0f) {
			return clampedCurrent;
		}
		if (dtSeconds >= 0.5f) {
			return clampedTarget;
		}
		float alpha = 1.0f - (float) Math.exp(-dtSeconds / FADE_TAU_SECONDS);
		float next = clampedCurrent + (clampedTarget - clampedCurrent) * alpha;
		return Math.abs(next - clampedTarget) < EPSILON ? clampedTarget : next;
	}

	/**
	 * Computes effective world radius in blocks for an item with light level {@code level} (0..15).
	 * Scales proportionally to the item's emission so a redstone torch (7) has a smaller halo than
	 * a torch (14) or lantern (15), keeping the radius tight to limit wall leaking.
	 */
	public static float effectiveRadius(float level, float configuredMaxRadius) {
		if (level <= 0.001f) {
			return 0.0f;
		}
		float maxR = clampRadius(configuredMaxRadius);
		float scale = Math.max(0.40f, Math.min(1.0f, level / 15.0f));
		return maxR * scale;
	}

	/**
	 * Converts an effective light level (0..15) and strength multiplier into Minecraft's packed
	 * lightmap block-light coordinate range ({@code 0..240}), matching {@code UV2.x}.
	 */
	public static float blockLight240(float level, float configuredStrength) {
		if (level <= 0.001f) {
			return 0.0f;
		}
		float s = clampStrength(configuredStrength);
		return Math.min(240.0f, Math.max(0.0f, level * s * 16.0f));
	}

	/**
	 * Evaluates the quadratic falloff contribution in {@code UV2.x} space ({@code 0..240}) at
	 * {@code distance} blocks from the held light. Matches the GLSL terrain shader formula.
	 */
	public static float evaluateLight240(float distance, float radius, float blockLight240) {
		if (blockLight240 <= 0.001f || radius <= 0.001f || distance >= radius) {
			return 0.0f;
		}
		float t = Math.max(0.0f, 1.0f - (distance / radius));
		return Math.min(240.0f, blockLight240 * t * t);
	}
}
