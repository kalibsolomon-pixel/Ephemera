package com.ephemera.core.visuals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HeldWorldLightMathTest {
	@Test
	void clampsRadiusAndStrengthSafely() {
		assertEquals(HeldWorldLightMath.DEFAULT_RADIUS, HeldWorldLightMath.clampRadius(Float.NaN));
		assertEquals(HeldWorldLightMath.DEFAULT_RADIUS, HeldWorldLightMath.clampRadius(0.0f));
		assertEquals(HeldWorldLightMath.MIN_RADIUS, HeldWorldLightMath.clampRadius(1.0f));
		assertEquals(HeldWorldLightMath.MAX_RADIUS, HeldWorldLightMath.clampRadius(40.0f));

		assertEquals(HeldWorldLightMath.DEFAULT_STRENGTH, HeldWorldLightMath.clampStrength(Float.NaN));
		assertEquals(HeldWorldLightMath.MIN_STRENGTH, HeldWorldLightMath.clampStrength(0.05f));
		assertEquals(HeldWorldLightMath.MAX_STRENGTH, HeldWorldLightMath.clampStrength(3.0f));
	}

	@Test
	void smoothLevelInterpolatesAndConverges() {
		float level = 0.0f;
		level = HeldWorldLightMath.smoothLevel(level, 14.0f, 0.016f);
		assertTrue(level > 1.5f && level < 5.0f, "Expected smooth ramp on 16 ms frame, got " + level);
		for (int i = 0; i < 60; i++) {
			level = HeldWorldLightMath.smoothLevel(level, 14.0f, 0.016f);
		}
		assertEquals(14.0f, level, 0.02f);

		// Switching off smoothly decays back to 0.
		float decayed = HeldWorldLightMath.smoothLevel(14.0f, 0.0f, 0.016f);
		assertTrue(decayed > 9.0f && decayed < 13.0f);
		assertEquals(0.0f, HeldWorldLightMath.smoothLevel(14.0f, 0.0f, 0.6f));
	}

	@Test
	void quadraticFalloffMatchesShortCaveRadiusAndZeroesAtCutoff() {
		float torchRadius = HeldWorldLightMath.effectiveRadius(14.0f, 8.5f);
		float redstoneRadius = HeldWorldLightMath.effectiveRadius(7.0f, 8.5f);
		assertTrue(torchRadius > redstoneRadius && torchRadius <= 8.5f);
		assertEquals(0.0f, HeldWorldLightMath.effectiveRadius(0.0f, 8.5f));

		float maxLight = HeldWorldLightMath.blockLight240(14.0f, 0.85f);
		assertTrue(maxLight > 180.0f && maxLight <= 240.0f);
		assertEquals(maxLight, HeldWorldLightMath.evaluateLight240(0.0f, torchRadius, maxLight), 0.01f);
		float mid = HeldWorldLightMath.evaluateLight240(torchRadius * 0.5f, torchRadius, maxLight);
		assertEquals(maxLight * 0.25f, mid, 0.05f);
		assertEquals(0.0f, HeldWorldLightMath.evaluateLight240(torchRadius, torchRadius, maxLight));
		assertEquals(0.0f, HeldWorldLightMath.evaluateLight240(torchRadius + 2.0f, torchRadius, maxLight));
	}
}
