package com.ephemera.core.pacing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FrameRateLimitPolicyTest {
	@Test
	void exactOrderedScaleMatchesSpecification() {
		assertEquals(38, FrameRateLimitPolicy.stopCount());
		// 10..250 step 10
		for (int i = 0; i < 25; i++) {
			assertEquals((i + 1) * 10, FrameRateLimitPolicy.stopAt(i));
		}
		// 260..300 step 10
		assertEquals(260, FrameRateLimitPolicy.stopAt(25));
		assertEquals(270, FrameRateLimitPolicy.stopAt(26));
		assertEquals(280, FrameRateLimitPolicy.stopAt(27));
		assertEquals(290, FrameRateLimitPolicy.stopAt(28));
		assertEquals(300, FrameRateLimitPolicy.stopAt(29));
		// 400..1000 step 100
		assertEquals(400, FrameRateLimitPolicy.stopAt(30));
		assertEquals(500, FrameRateLimitPolicy.stopAt(31));
		assertEquals(600, FrameRateLimitPolicy.stopAt(32));
		assertEquals(700, FrameRateLimitPolicy.stopAt(33));
		assertEquals(800, FrameRateLimitPolicy.stopAt(34));
		assertEquals(900, FrameRateLimitPolicy.stopAt(35));
		assertEquals(1000, FrameRateLimitPolicy.stopAt(36));
		// Final stop: Unlimited
		assertEquals(FrameRateLimitPolicy.UNLIMITED, FrameRateLimitPolicy.stopAt(37));
	}

	@Test
	void sliderRoundTripsEveryStopAndStepsCleanly() {
		for (int i = 0; i < FrameRateLimitPolicy.stopCount(); i++) {
			int stop = FrameRateLimitPolicy.stopAt(i);
			assertTrue(FrameRateLimitPolicy.isValidStop(stop));
			double frac = FrameRateLimitPolicy.toSliderFraction(stop);
			assertEquals(stop, FrameRateLimitPolicy.fromSliderFraction(frac), "stop=" + stop);
			if (i + 1 < FrameRateLimitPolicy.stopCount()) {
				assertEquals(FrameRateLimitPolicy.stopAt(i + 1), FrameRateLimitPolicy.step(stop, 1));
			} else {
				assertEquals(stop, FrameRateLimitPolicy.step(stop, 1));
			}
			if (i > 0) {
				assertEquals(FrameRateLimitPolicy.stopAt(i - 1), FrameRateLimitPolicy.step(stop, -1));
			} else {
				assertEquals(stop, FrameRateLimitPolicy.step(stop, -1));
			}
		}
	}

	@Test
	void resolveEffectiveLimitPreservesVanillaThrottlesAndLegacyUnlimited() {
		// Legacy vanilla Unlimited (260) with default extended=0 remains Unlimited (0)
		assertEquals(0, FrameRateLimitPolicy.resolveEffectiveLimit(260, 0));
		// Vanilla 10..250 cap wins
		assertEquals(140, FrameRateLimitPolicy.resolveEffectiveLimit(140, 0));
		assertEquals(140, FrameRateLimitPolicy.resolveEffectiveLimit(140, 500));
		// Runtime throttles (minimized=10, AFK=30, menu=60) always beat extended 500 fps cap
		assertEquals(10, FrameRateLimitPolicy.resolveEffectiveLimit(10, 500));
		assertEquals(30, FrameRateLimitPolicy.resolveEffectiveLimit(30, 500));
		assertEquals(60, FrameRateLimitPolicy.resolveEffectiveLimit(60, 1000));
		// Unthrottled vanilla (260) activates extended 260..1000 cap
		assertEquals(260, FrameRateLimitPolicy.resolveEffectiveLimit(260, 260));
		assertEquals(300, FrameRateLimitPolicy.resolveEffectiveLimit(260, 300));
		assertEquals(600, FrameRateLimitPolicy.resolveEffectiveLimit(260, 600));
		assertEquals(1000, FrameRateLimitPolicy.resolveEffectiveLimit(260, 1000));
	}

	@Test
	void sanitizeExtendedSnapsAndRejectsOutOfRangeValues() {
		assertEquals(0, FrameRateLimitPolicy.sanitizeExtended(0));
		assertEquals(0, FrameRateLimitPolicy.sanitizeExtended(250));
		assertEquals(260, FrameRateLimitPolicy.sanitizeExtended(260));
		assertEquals(270, FrameRateLimitPolicy.sanitizeExtended(268));
		assertEquals(300, FrameRateLimitPolicy.sanitizeExtended(320));
		assertEquals(400, FrameRateLimitPolicy.sanitizeExtended(360));
		assertEquals(700, FrameRateLimitPolicy.sanitizeExtended(740));
		assertEquals(1000, FrameRateLimitPolicy.sanitizeExtended(2500));
	}
}
