package com.ephemera.core.pacing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class EphemeraFramePacerTest {
	private static final class FakeWaitOps implements EphemeraFramePacer.WaitOps {
		long nowNs;
		long parkOvershootNs;
		long earlyWakeActualNs = -1L;
		boolean interrupted;
		final List<Long> parkRequests = new ArrayList<>();
		int spins;

		FakeWaitOps(long startNs) {
			this.nowNs = startNs;
		}

		@Override
		public long nanoTime() {
			return nowNs;
		}

		@Override
		public void parkNanos(long nanos) {
			parkRequests.add(nanos);
			if (earlyWakeActualNs >= 0L) {
				nowNs += earlyWakeActualNs;
				earlyWakeActualNs = -1L;
			} else {
				nowNs += Math.max(0L, nanos + parkOvershootNs);
			}
		}

		@Override
		public void onSpinWait() {
			spins++;
			nowNs += 50_000L; // 50 us spin step
		}

		@Override
		public boolean isInterrupted() {
			return interrupted;
		}
	}

	@Test
	void frameHeadArmsThenPacesAndSuppressesDuplicatePostSwap() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		// Frame 1: arming returns 0 wait, but frame-head still owns the cadence so vanilla is cancelled
		assertEquals(0L, pacer.paceFrameHead(120, true, 500));
		assertTrue(pacer.pacePostSwap(120, 500), "Vanilla must not sleep on the frame frame-head just armed");

		// Frame 2: on time, waits until exact deadline and suppresses post-swap
		ops.nowNs += 2_000_000L; // 2 ms work, ~6.33 ms left in 120 fps period
		long waited = pacer.paceFrameHead(120, true, 500);
		assertTrue(waited > 5_000_000L && waited < 7_000_000L);
		assertTrue(pacer.pacePostSwap(120, 500), "Post-swap must be cancelled when frame-head already waited");
		assertEquals(1, ops.parkRequests.size());
	}

	@Test
	void frameHeadPostHitchReanchorStillCancelsVanillaPostSwap() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		assertEquals(0L, pacer.paceFrameHead(120, true, 500));
		assertTrue(pacer.pacePostSwap(120, 500));
		ops.nowNs += 2_000_000L;
		assertTrue(pacer.paceFrameHead(120, true, 500) > 0L);
		assertTrue(pacer.pacePostSwap(120, 500));
		int parksBeforeHitch = ops.parkRequests.size();

		// 50 ms hitch (> 0.75 * 8.33 ms period): frame-head re-anchors without waiting...
		ops.nowNs += 50_000_000L;
		assertEquals(0L, pacer.paceFrameHead(120, true, 500));
		// ...and post-swap must still cancel vanilla so the re-anchored frame is not waited twice.
		assertTrue(pacer.pacePostSwap(120, 500), "Post-hitch re-anchor must not hand the wait back to vanilla");
		assertEquals(parksBeforeHitch, ops.parkRequests.size());
		assertEquals(EphemeraFramePacer.HookPoint.FRAME_HEAD, pacer.activeOwner());
	}

	@Test
	void hugeUptimeNanoTimePreservesExactFractionalCadence() {
		long uptimeNs = 1_000_000_000_000_000L; // ~11.5 days, far beyond double's exact-ns range
		for (int fps : new int[] {144, 240}) {
			EphemeraFramePacer pacer = new EphemeraFramePacer(new FakeWaitOps(uptimeNs));
			double periodNs = 1_000_000_000.0 / fps;
			assertEquals(0L, pacer.advanceDeadline(EphemeraFramePacer.HookPoint.POST_SWAP, fps, uptimeNs));

			int frames = 10_000;
			for (int k = 1; k <= frames; k++) {
				long expected = uptimeNs + Math.round(k * periodNs);
				// Arrive slightly early each frame so only the on-time cadence path is exercised.
				long target = pacer.advanceDeadline(EphemeraFramePacer.HookPoint.POST_SWAP, fps, expected - 1_000L);
				assertEquals(expected, target, "fps=" + fps + " frame=" + k);
			}
		}
	}

	@Test
	void adaptiveOvershootWidensReserveQuicklyOnWindowsTimerJitter() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		ops.parkOvershootNs = 1_600_000L; // 1.6 ms late wakeups
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		int initialMicros = pacer.adaptiveOvershootMicros();
		for (int i = 0; i < 6; i++) {
			pacer.observeParkOvershoot(5_000_000L, 6_600_000L);
		}
		int adaptedMicros = pacer.adaptiveOvershootMicros();
		assertTrue(adaptedMicros > initialMicros + 800,
			"Fast-attack overshoot estimator should adapt within a few frames: " + adaptedMicros);
		assertTrue(pacer.effectiveReserveNs(500_000L) > 1_200_000L);
	}

	@Test
	void earlyWakeupReparksBoundedInsteadOfSpinningForMilliseconds() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		ops.earlyWakeActualNs = 100_000L; // spurious 0.1 ms wake on first park
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		long deadline = ops.nowNs + 10_000_000L; // 10 ms deadline
		pacer.waitUntil(deadline, 500);
		assertEquals(2, ops.parkRequests.size(), "Early wakeup should repark once rather than spinning 9.9 ms");
		assertTrue(ops.nowNs >= deadline);
	}

	@Test
	void mildlyLateFrameAppliesSoftPhaseRecoveryWithoutSawtooth() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		long period = 10_000_000L; // 100 fps
		assertEquals(0L, pacer.advanceDeadline(EphemeraFramePacer.HookPoint.FRAME_HEAD, 100, ops.nowNs));

		// Frame arrives 3 ms late (within 0.75 * period): returns past deadline (immediate exit)
		// and shifts next deadline forward by half the lateness (+1.5 ms) to damp sawtooth oscillation.
		long lateNow = ops.nowNs + period + 3_000_000L;
		long target1 = pacer.advanceDeadline(EphemeraFramePacer.HookPoint.FRAME_HEAD, 100, lateNow);
		assertEquals(ops.nowNs + period, target1);

		long target2 = pacer.advanceDeadline(EphemeraFramePacer.HookPoint.FRAME_HEAD, 100, lateNow + 1_000_000L);
		assertEquals(ops.nowNs + 2 * period + 1_500_000L, target2);
	}

	@Test
	void captureModeCapsSpinReserveEvenAfterHeavyOvershoot() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		// Fresh pacer: normal mode uses adaptive reserve (> 300 us), capture mode caps at 300 us
		assertTrue(pacer.effectiveReserveNs(300_000L, false) > 600_000L);
		assertEquals(300_000L, pacer.effectiveReserveNs(300_000L, true));

		// Feed repeated 4 ms overshoots that push adaptive reserve to 3.5 ms in normal mode
		for (int i = 0; i < 20; i++) {
			pacer.observeParkOvershoot(1_000_000L, 5_000_000L);
		}
		assertEquals(3_500_000L, pacer.effectiveReserveNs(300_000L, false));
		assertEquals(300_000L, pacer.effectiveReserveNs(300_000L, true));

		// Clean wake in capture mode: parks almost the full 10 ms and spins <= 300 us (<= 6 * 50 us steps)
		ops.parkOvershootNs = 0L;
		long deadline1 = ops.nowNs + 10_000_000L;
		pacer.waitUntil(deadline1, 300, true);
		assertEquals(1, ops.parkRequests.size());
		assertEquals(9_700_000L, ops.parkRequests.get(0));
		assertTrue(ops.spins <= 6, "Capture spin tail must stay bounded at 300 us, got spins=" + ops.spins);

		// Late wake in capture mode: zero busy-spinning after oversleeping past deadline
		ops.parkRequests.clear();
		ops.spins = 0;
		ops.parkOvershootNs = 800_000L;
		long deadline2 = ops.nowNs + 10_000_000L;
		pacer.waitUntil(deadline2, 300, true);
		assertEquals(1, ops.parkRequests.size());
		assertEquals(0, ops.spins, "Late wake past deadline must not spin");
	}

	@Test
	void endingWorkBeforeLimiterSleepPreservesUploadBudgetHeadroom() {
		FrameBudgetPacer cleanBudget = new FrameBudgetPacer();
		FrameBudgetPacer pollutedBudget = new FrameBudgetPacer();
		long t = 1_000_000_000L;
		long periodNs = 16_666_667L; // 60 fps
		long workNs = 4_000_000L;    // 4 ms pure CPU work

		cleanBudget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 60);
		pollutedBudget.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, 60);

		for (int i = 0; i < 10; i++) {
			cleanBudget.beginFrameWork(t);
			cleanBudget.endFrameWork(t + workNs); // ended before limiter sleep
			pollutedBudget.beginFrameWork(t);
			pollutedBudget.endFrameWork(t + periodNs); // erroneously included sleep
			t += periodNs;
		}

		long cleanUploadNs = cleanBudget.computeUploadBudgetNs(8, true, t);
		long pollutedUploadNs = pollutedBudget.computeUploadBudgetNs(8, true, t);
		assertTrue(cleanUploadNs >= 5_000_000L, "4 ms work at 60 fps should leave >5 ms upload budget, got " + cleanUploadNs);
		assertEquals(500_000L, pollutedUploadNs, "Including sleep collapses budget to 0.5 ms floor");
	}
}
