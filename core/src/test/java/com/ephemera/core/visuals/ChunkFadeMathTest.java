package com.ephemera.core.visuals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkFadeMathTest {
	@Test
	void easeOutCubicHitsEndpointsAndClamps() {
		assertEquals(0.0f, ChunkFadeMath.easeOutCubic(0.0f), 1e-6f);
		assertEquals(1.0f, ChunkFadeMath.easeOutCubic(1.0f), 1e-6f);
		assertEquals(0.0f, ChunkFadeMath.easeOutCubic(-3.0f), 1e-6f);
		assertEquals(1.0f, ChunkFadeMath.easeOutCubic(7.0f), 1e-6f);
	}

	@Test
	void easeOutCubicMatchesClosedForm() {
		assertEquals(0.875f, ChunkFadeMath.easeOutCubic(0.5f), 1e-6f);
		assertEquals(1.0f - 0.8f * 0.8f * 0.8f, ChunkFadeMath.easeOutCubic(0.2f), 1e-6f);
	}

	@Test
	void easeOutCubicIsMonotonicAndAheadOfLinear() {
		float prev = 0.0f;
		for (int i = 1; i <= 100; i++) {
			float t = i / 100.0f;
			float v = ChunkFadeMath.easeOutCubic(t);
			assertTrue(v >= prev, "monotonic at t=" + t);
			assertTrue(v >= t - 1e-6f, "ease-out never trails linear at t=" + t);
			prev = v;
		}
	}

	@Test
	void fadeVisibilityHandlesDegenerateDurations() {
		assertEquals(1.0f, ChunkFadeMath.fadeVisibility(10L, 0L));
		assertEquals(1.0f, ChunkFadeMath.fadeVisibility(1000L, 650L));
		assertEquals(0.0f, ChunkFadeMath.fadeVisibility(0L, 650L));
		assertEquals(0.0f, ChunkFadeMath.fadeVisibility(-5L, 650L));
		assertEquals(0.875f, ChunkFadeMath.fadeVisibility(325L, 650L), 1e-6f);
	}

	@Test
	void fadeDurationConvertsSecondsToMillis() {
		assertEquals(650L, ChunkFadeMath.fadeDurationMs(0.65f));
		assertEquals(0L, ChunkFadeMath.fadeDurationMs(0.0f));
		assertEquals(0L, ChunkFadeMath.fadeDurationMs(-1.0f));
		assertEquals(0L, ChunkFadeMath.fadeDurationMs(Float.NaN));
	}

	@Test
	void aoContrastIsIdentityAtOneAndKeepsLitCornersLit() {
		for (int i = 0; i <= 10; i++) {
			float ao = i / 10.0f;
			assertEquals(ao, ChunkFadeMath.aoContrast(ao, 1.0f), 1e-6f);
		}
		assertEquals(1.0f, ChunkFadeMath.aoContrast(1.0f, 1.5f), 1e-6f);
		assertEquals(0.0f, ChunkFadeMath.aoContrast(0.0f, 1.5f), 1e-6f);
	}

	@Test
	void aoContrastDeepensShadowsAboveOneAndLiftsBelowOne() {
		assertTrue(ChunkFadeMath.aoContrast(0.5f, 1.2f) < 0.5f);
		assertTrue(ChunkFadeMath.aoContrast(0.5f, 0.8f) > 0.5f);
		assertEquals((float) Math.pow(0.6, 1.08), ChunkFadeMath.aoContrast(0.6f, 1.08f), 1e-6f);
	}

	@Test
	void aoLutIsMonotonicWithFixedEndpoints() {
		int[] lut = ChunkFadeMath.buildAoLut(1.08f);
		assertEquals(256, lut.length);
		assertEquals(0, lut[0]);
		assertEquals(255, lut[255]);
		for (int i = 1; i < 256; i++) {
			assertTrue(lut[i] >= lut[i - 1], "monotonic at " + i);
			assertTrue(lut[i] <= i, "strength > 1 never brightens at " + i);
		}
		int[] identity = ChunkFadeMath.buildAoLut(1.0f);
		for (int i = 0; i < 256; i++) {
			assertEquals(i, identity[i]);
		}
	}
}
