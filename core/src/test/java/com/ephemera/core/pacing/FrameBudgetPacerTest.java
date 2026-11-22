package com.ephemera.core.pacing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FrameBudgetPacerTest {
	@Test
	void externalAuroraPacerTakesOwnershipAndDrivesTighterUploadBudget() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		long t = 1_000_000_000L;

		budget.reportExternalPacer(true, 240, 2_000_000L, t);
		assertTrue(budget.isExternalPacerActive(t + 10_000_000L));
		assertEquals(FrameBudgetPacer.PacerOwner.AURORA, budget.owner());
		assertEquals(240, budget.effectiveTargetFps(t + 10_000_000L, 0));

		// Configured max is 8 ms, but at 240 fps (4.16 ms period) upload budget must tighten well under 2 ms.
		budget.beginFrameWork(t);
		budget.endFrameWork(t + 1_500_000L);
		budget.beginFrameWork(t + 4_166_000L);
		long uploadBudgetNs = budget.computeUploadBudgetNs(8, true, t + 4_666_000L);
		assertTrue(uploadBudgetNs <= 1_500_000L && uploadBudgetNs >= 500_000L,
			"Expected tight upload budget at 240 fps, got " + uploadBudgetNs);
	}

	@Test
	void alwaysAllowsAtLeastOneSectionUploadPerFrame() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		assertTrue(budget.allowSectionUpload(0, 10_000_000L, 500_000L));
		assertFalse(budget.allowSectionUpload(1, 600_000L, 500_000L));
		assertEquals(1L, budget.throttledUploadCount());
	}

	@Test
	void expiresStaleExternalPacerOwnershipCleanly() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		budget.reportExternalPacer(true, 144, 0L, 1_000_000_000L);
		assertTrue(budget.isExternalPacerActive(2_500_000_000L));
		assertFalse(budget.isExternalPacerActive(3_500_000_000L));
	}
}
