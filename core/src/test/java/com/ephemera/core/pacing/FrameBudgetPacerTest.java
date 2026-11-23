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

	@Test
	void cappedFrameDoesNotInflateUploadBudgetPastRemainingHeadroom() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		long t = 1_000_000_000L;
		budget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 240); // 4.166 ms period, ~694 us cushion
		budget.beginFrameWork(t);
		budget.endFrameWork(t + 3_300_000L); // 3.30 ms typical work -> only ~172 us headroom left!
		budget.beginFrameWork(t + 4_166_666L);
		long uploadBudgetNs = budget.computeUploadBudgetNs(4, true, t + 4_166_666L + 3_300_000L);
		assertTrue(uploadBudgetNs <= 175_000L && uploadBudgetNs >= 0L,
			"240 FPS frame with ~172 us headroom must NOT receive a 500 us floor; got " + uploadBudgetNs);
	}

	@Test
	void sharedStreamingDeadlineSpansSnapshotsAndUploadsWithoutStacking() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		long t0 = 5_000_000_000L;
		budget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 240);
		budget.beginFrameWork(t0);
		budget.endFrameWork(t0 + 2_500_000L);

		long f2 = t0 + 4_166_666L;
		budget.beginFrameWork(f2);
		long stage1Start = f2 + 500_000L;
		long d1 = budget.resolveStreamingDeadlineNs(4, true, 4_000_000L, stage1Start);
		// Second stage later in the same frame receives the EXACT SAME latched deadline.
		long d2 = budget.resolveStreamingDeadlineNs(4, true, 4_000_000L, stage1Start + 400_000L);
		assertEquals(d1, d2);

		// Snapshot stage does 2 items and hits the shared deadline.
		assertTrue(budget.allowStreamingWork(stage1Start, d1, 2));
		budget.recordStreamingWork();
		assertTrue(budget.allowStreamingWork(stage1Start + 100_000L, d1, 2));
		budget.recordStreamingWork();

		// Once past the shared deadline, mesh uploads immediately stop instead of claiming a second budget.
		assertFalse(budget.allowStreamingWork(d1 + 1L, d2, 1));
		assertEquals(1, budget.frameStreamingThrottled());

		// Next frame resets the shared streaming state cleanly.
		budget.beginFrameWork(f2 + 4_166_666L);
		assertEquals(0, budget.frameStreamingItemsDone());
		assertEquals(0, budget.frameStreamingThrottled());
		assertEquals(0L, budget.frameStreamingSpentNs());
	}

	@Test
	void uncappedNonStreamingWorkBetweenStagesDoesNotStarveGpuUploads() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		long t0 = 8_000_000_000L;
		budget.setActiveOwner(FrameBudgetPacer.PacerOwner.VANILLA, 0); // uncapped
		budget.beginFrameWork(t0);
		budget.endFrameWork(t0 + 400_000L);

		long f2 = t0 + 500_000L;
		budget.beginFrameWork(f2);
		long d1 = budget.resolveStreamingDeadlineNs(4, true, 4_000_000L, f2);
		assertEquals(Long.MAX_VALUE, d1);

		// Stage 1 spends 2.3 ms on snapshots (exceeds 75% snapshot reserve of 2.25 ms out of 3.0 ms).
		assertTrue(budget.allowSnapshotStreamingWork(1, true));
		budget.recordStreamingWork(2_300_000L);
		assertFalse(budget.allowSnapshotStreamingWork(1, true));

		// Even if 10 ms of unrelated entity/world work happened before stage 2, GPU uploads still
		// have the reserved 0.7 ms slice of the shared 3.0 ms budget.
		assertTrue(budget.allowStreamingWork(f2 + 10_000_000L, d1, 1));
		budget.recordStreamingWork(800_000L);

		// Now shared spent time (3.1 ms) exceeds the 3.0 ms budget -> stops cleanly.
		assertFalse(budget.allowStreamingWork(1));
		assertEquals(2, budget.frameStreamingThrottled());
	}
}
