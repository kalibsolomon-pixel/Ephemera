package com.ephemera.core.visuals;

/**
 * Pure math for the chunk fade-in curve and the smooth-lighting AO contrast curve.
 */
public final class ChunkFadeMath {
	private ChunkFadeMath() {}

	/** Cubic ease-out: {@code 1 - (1 - t)^3}, clamped to [0, 1]. Fast start, soft landing. */
	public static float easeOutCubic(float t) {
		if (t <= 0.0f) {
			return 0.0f;
		}
		if (t >= 1.0f) {
			return 1.0f;
		}
		float inv = 1.0f - t;
		return 1.0f - inv * inv * inv;
	}

	/** Maps vanilla's linear {@code elapsed / duration} fade progress onto the eased curve. */
	public static float fadeVisibility(long elapsedMs, long durationMs) {
		if (durationMs <= 0L || elapsedMs >= durationMs) {
			return 1.0f;
		}
		if (elapsedMs <= 0L) {
			return 0.0f;
		}
		return easeOutCubic((float) elapsedMs / (float) durationMs);
	}

	public static long fadeDurationMs(float seconds) {
		if (!(seconds > 0.0f)) {
			return 0L;
		}
		return Math.round(seconds * 1000.0);
	}

	/**
	 * AO contrast: {@code ao^strength}. 1.0 is the identity; fully lit corners (1.0) never change,
	 * occluded corners deepen as strength rises and lighten as it falls.
	 */
	public static float aoContrast(float ao, float strength) {
		if (ao <= 0.0f) {
			return 0.0f;
		}
		if (ao >= 1.0f || strength == 1.0f) {
			return Math.min(ao, 1.0f);
		}
		return (float) Math.pow(ao, strength);
	}

	/** 256-entry byte lookup table of {@link #aoContrast} for 8-bit channels. */
	public static int[] buildAoLut(float strength) {
		int[] lut = new int[256];
		for (int i = 0; i < 256; i++) {
			float adjusted = aoContrast(i / 255.0f, strength);
			lut[i] = Math.max(0, Math.min(255, Math.round(adjusted * 255.0f)));
		}
		return lut;
	}
}
