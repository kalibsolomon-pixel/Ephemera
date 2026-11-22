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

		// Frame 1: arming returns 0 wait
		assertEquals(0L, pacer.paceFrameHead(120, true, 500));
		assertFalse(pacer.pacePostSwap(120, 500));

		// Frame 2: on time, waits until exact deadline and suppresses post-swap
		ops.nowNs += 2_000_000L; // 2 ms work, ~6.33 ms left in 120 fps period
		long waited = pacer.paceFrameHead(120, true, 500);
		assertTrue(waited > 5_000_000L && waited < 7_000_000L);
		assertTrue(pacer.pacePostSwap(120, 500), "Post-swap must be cancelled when frame-head already waited");
		assertEquals(1, ops.parkRequests.size());
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
}
