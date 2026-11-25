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
		assertEquals(0L, pollutedUploadNs, "Including sleep collapses capped budget to 0 ns instead of inflating to a 0.5 ms floor");
	}

	@Test
	void twoStagePredictivePacingEliminates20HzTickSawtoothAt240Fps() {
		FakeWaitOps ops = new FakeWaitOps(10_000_000_000L);
		ops.parkOvershootNs = 0L;
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		int fps = 240;
		long periodNs = Math.round(1_000_000_000.0 / fps); // 4,166,667 ns
		List<Long> presentTimestamps = new ArrayList<>();

		// Simulate 60 frames: every 12th frame is a 20 Hz game tick taking 2.3 ms instead of 0.65 ms.
		for (int f = 0; f < 60; f++) {
			boolean isTick = (f % 12) == 0;
			pacer.paceTwoStageHead(fps, true, isTick, 500, false);
			long workStart = ops.nowNs;
			long cpuWork = isTick ? 2_300_000L : 650_000L;
			ops.nowNs += cpuWork;
			pacer.observeCpuWork(ops.nowNs - workStart, isTick);
			assertTrue(pacer.paceTwoStagePrePresent(fps, 500, false));
			presentTimestamps.add(ops.nowNs);
			// Post-swap must cancel vanilla so we never double-wait.
			assertTrue(pacer.pacePostSwap(fps, 500, false));
		}

		// After initial 15 frames of estimator warmup, EVERY present-to-present interval (including
		// across 20 Hz tick frames!) must be within +/- 100 us of the exact 4.167 ms period.
		long maxDeviationNs = 0L;
		for (int i = 16; i < presentTimestamps.size(); i++) {
			long dt = presentTimestamps.get(i) - presentTimestamps.get(i - 1);
			maxDeviationNs = Math.max(maxDeviationNs, Math.abs(dt - periodNs));
		}
		assertTrue(maxDeviationNs <= 100_000L,
			"Two-stage pacing should hold flat 240 FPS across 20 Hz ticks; max deviation=" + (maxDeviationNs / 1000L) + " us");
	}

	@Test
	void highFpsCapsBoundSpinReserveSo500And1000FpsStillPark() {
		FakeWaitOps ops = new FakeWaitOps(1_000_000_000L);
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		// Inject heavy OS wake jitter (3 ms) that would normally push reserve to 3.5 ms
		for (int i = 0; i < 15; i++) {
			pacer.observeParkOvershoot(1_000_000L, 4_000_000L);
		}
		// At 1000 FPS (1.0 ms period), reserve must clamp <= 600 us so idle time still parks
		long res1000 = pacer.effectiveReserveNs(500_000L, false, 1_000_000L);
		assertTrue(res1000 <= 600_000L && res1000 >= 200_000L, "1000 FPS reserve=" + res1000);
		// At 500 FPS (2.0 ms period), reserve must clamp <= 1.2 ms
		long res500 = pacer.effectiveReserveNs(500_000L, false, 2_000_000L);
		assertTrue(res500 <= 1_200_000L && res500 >= 200_000L, "500 FPS reserve=" + res500);
	}

	@Test
	void twoStageExactTickTimingAvoidsFalseEarlyWakeAndResistsGcSpike() {
		FakeWaitOps ops = new FakeWaitOps(10_000_000_000L);
		ops.parkOvershootNs = 0L;
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		int fps = 240; // 4.167 ms period
		// Arm and train estimators: non-tick ~0.4 ms, tick ~2.0 ms
		pacer.paceTwoStageHead(fps, true, Long.MAX_VALUE, 500, false);
		pacer.paceTwoStagePrePresent(fps, 500, false);
		for (int i = 0; i < 12; i++) {
			pacer.observeCpuWork(400_000L, false);
			pacer.observeCpuWork(2_000_000L, true);
		}

		// Case 1: Tick is 4.5 ms away (> normalWake + 1 ms) -> must NOT wake early!
		long start1 = ops.nowNs;
		pacer.paceTwoStageHead(fps, true, 4_500_000L, 500, false);
		long waited1 = ops.nowNs - start1;
		assertTrue(waited1 > 3_100_000L, "Should wait close to normalWake (~3.4 ms), waited=" + (waited1 / 1000L) + " us");
		ops.nowNs += 400_000L;
		pacer.paceTwoStagePrePresent(fps, 500, false);

		// Case 2: Tick is 2.6 ms away (after tickWake ~1.8 ms, before normalWake ~3.4 ms) ->
		// must wait until tickDue + 0.5 ms (~3.1 ms), NOT prematurely at tickWake (~1.8 ms).
		long start2 = ops.nowNs;
		pacer.paceTwoStageHead(fps, true, 2_600_000L, 500, false);
		long waited2 = ops.nowNs - start2;
		assertTrue(waited2 >= 3_000_000L && waited2 <= 3_300_000L,
			"Should wake just after tick boundary (~3.1 ms), waited=" + (waited2 / 1000L) + " us");

		// Case 3: Single 20 ms GC outlier must not blow up predictedTickWorkMs or linger for seconds.
		double beforeGc = pacer.predictedTickWorkMs();
		pacer.observeCpuWork(20_000_000L, true);
		assertTrue(pacer.predictedTickWorkMs() < beforeGc + 0.5,
			"Single GC outlier should stay damped; before=" + beforeGc + " after=" + pacer.predictedTickWorkMs());
		for (int i = 0; i < 12; i++) {
			pacer.observeCpuWork(1_800_000L, true);
		}
		assertTrue(pacer.predictedTickWorkMs() < 2.1, "Tick estimator should decay quickly within 12 ticks");
	}

	@Test
	void prePresentStateResetsOnUncappedTransitionAndAccountsForBlockingSwap() {
		FakeWaitOps ops = new FakeWaitOps(10_000_000_000L);
		EphemeraFramePacer pacer = new EphemeraFramePacer(ops);

		// Arm PRE_PRESENT at 240 FPS
		pacer.paceTwoStageHead(240, true, Long.MAX_VALUE, 500, false);
		pacer.paceTwoStagePrePresent(240, 500, false);
		assertEquals(EphemeraFramePacer.HookPoint.PRE_PRESENT, pacer.activeOwner());

		// Observe a 0.8 ms blocking swap (e.g. GL glfwSwapBuffers)
		ops.nowNs += 800_000L;
		pacer.observePostPresent(ops.nowNs, 240);
		assertEquals(EphemeraFramePacer.HookPoint.PRE_PRESENT, pacer.activeOwner());

		// Transition to uncapped / VSync via paceFrameHead(0, false, ...) must clear PRE_PRESENT ownership
		pacer.paceFrameHead(0, false, 500, false);
		assertEquals(EphemeraFramePacer.HookPoint.NONE, pacer.activeOwner());
		assertEquals(0L, pacer.latchedTargetPresentNs());
	}
}
