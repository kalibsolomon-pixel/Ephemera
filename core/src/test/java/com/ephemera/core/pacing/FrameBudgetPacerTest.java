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
		// Sub-ms uncapped frame should NOT receive a 3.0 ms floor spike; budget clamps cleanly to 1.0 ms.
		assertEquals(1_000_000L, budget.frameStreamingBudgetNs());

		// Stage 1 spends 0.8 ms on snapshots (exceeds 75% snapshot reserve of 0.75 ms out of 1.0 ms).
		assertTrue(budget.allowSnapshotStreamingWork(1, true));
		budget.recordStreamingWork(800_000L);
		assertFalse(budget.allowSnapshotStreamingWork(1, true));

		// Even if 10 ms of unrelated entity/world work happened before stage 2, GPU uploads still
		// have the reserved 0.2 ms slice of the shared 1.0 ms budget.
		assertTrue(budget.allowStreamingWork(f2 + 10_000_000L, d1, 1));
		budget.recordStreamingWork(300_000L);

		// Now shared spent time (1.1 ms) exceeds the 1.0 ms budget -> stops cleanly.
		assertFalse(budget.allowStreamingWork(1));
		assertEquals(2, budget.frameStreamingThrottled());
	}

	@Test
	void priorityFloorDoesNotPileExtraItemsOntoAlreadyLateCappedFrame() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		long t0 = 9_000_000_000L;
		budget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 240);
		budget.beginFrameWork(t0);
		long deadline = budget.resolveStreamingDeadlineNs(4, true, 4_000_000L, t0 + 100_000L);

		// First item is allowed even after deadline so streaming can never starve...
		assertTrue(budget.allowStreamingWork(deadline + 50_000L, deadline, 4));
		budget.recordStreamingWork(200_000L);
		// ...but item #2 must stop immediately despite priorityFloor=4 once past hard deadline!
		assertFalse(budget.allowStreamingWork(deadline + 250_000L, deadline, 4));
	}

	@Test
	void latchedPresentTargetAndCadenceTimestampDriveAccurateStreamingDeadline() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		long t0 = 20_000_000_000L;
		budget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 240); // 4.166 ms period
		// Feed clean 4.166 ms cadence timestamps while deliberately staggering beginFrameWork (Stage 1 tick wake).
		for (int i = 0; i < 6; i++) {
			long cadence = t0 + i * 4_166_666L;
			long workStart = cadence + ((i & 1) == 0 ? 500_000L : 1_800_000L);
			budget.beginFrameWork(workStart);
			budget.observeCadenceTimestamp(cadence);
			budget.endFrameWork(workStart + 1_200_000L);
		}
		assertTrue(budget.jitterMs() < 0.05,
			"Deliberate Stage 1 tick-wake shifts must not inflate cadence jitter; got " + budget.jitterMs() + " ms");

		long frameStart = t0 + 6 * 4_166_666L + 1_500_000L;
		long targetPresent = t0 + 7 * 4_166_666L;
		budget.beginFrameWork(frameStart);
		budget.setLatchedPresentTargetNs(targetPresent);
		long deadline = budget.resolveStreamingDeadlineNs(4, true, 4_000_000L, frameStart + 200_000L);
		assertTrue(deadline < targetPresent,
			"Streaming deadline must lie strictly before latched targetPresent; got " + deadline + " vs " + targetPresent);
		assertTrue(budget.remainingStreamingHeadroomNs(deadline - 300_000L) >= 300_000L);
		assertEquals(0L, budget.remainingStreamingHeadroomNs(deadline + 1L));
	}

	@Test
	void cappedFrameDeadlineExpiresAndDenialStreaksStayDenied() {
		FrameBudgetPacer budget = new FrameBudgetPacer();
		budget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 240);
		long now = System.nanoTime();
		budget.beginFrameWork(now);
		// Generous budget so the SPENT check never binds: every denial below must come from the
		// cached wall clock actually observing the latched deadline expire.
		long deadline = budget.resolveStreamingDeadlineNs(8, true, 4_000_000L, now);
		assertTrue(deadline < Long.MAX_VALUE, "capped frame must latch a hard deadline");

		// Admit a small batch with negligible recorded work (1 us each, far under both the
		// budget and the 250 us refresh quantum) so the loop ends well before expiry.
		int admitted = 0;
		while (admitted < 3 && budget.allowStreamingWork(1)) {
			budget.recordStreamingWork(1_000L);
			admitted++;
		}
		assertEquals(3, admitted, "fresh capped frame with free budget must admit the batch");
		assertTrue(budget.frameStreamingSpentNs() < budget.frameStreamingBudgetNs(),
			"spent must stay under budget so later denials are clock-driven");

		// Push the real wall clock well past the latched deadline, then confirm the cached clock
		// admits at most a bounded stale window (8 items since the last read) before denying for
		// the remainder of the frame — with spent still below budget, proving denials are
		// deadline-driven, not budget-driven.
		while (System.nanoTime() - now < 12_000_000L) {
			Thread.onSpinWait();
		}
		int staleAdmitted = 0;
		while (staleAdmitted < 64 && budget.allowStreamingWork(1)) {
			budget.recordStreamingWork(1_000L);
			staleAdmitted++;
		}
		assertTrue(staleAdmitted <= 8,
			"post-expiry stale admissions must be bounded by the cache quantum, got " + staleAdmitted);
		assertTrue(budget.frameStreamingSpentNs() < budget.frameStreamingBudgetNs(),
			"denials must be deadline-driven (spent stayed under budget)");
		for (int i = 0; i < 24; i++) {
			assertFalse(budget.allowStreamingWork(1), "expired deadline must deny (call " + i + ")");
			assertFalse(budget.hasSnapshotStreamingHeadroom(false), "expired deadline must deny headroom");
		}
		// Stage 2 re-entry: resolveStreamingDeadlineNs re-seeds the clock from the caller's fresh
		// timestamp, which is again past the deadline, so gates must stay denied.
		budget.resolveStreamingDeadlineNs(8, true, 4_000_000L, System.nanoTime());
		assertFalse(budget.allowStreamingWork(1), "post-reseed expired deadline must deny");
	}
}
