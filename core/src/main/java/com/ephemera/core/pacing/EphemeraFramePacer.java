package com.ephemera.core.pacing;

import java.util.concurrent.locks.LockSupport;

/**
 * Pure-Java precision frame pacer for Ephemera.
 *
 * <p>Key improvements over vanilla {@code FramerateLimiter}:
 * <ul>
 *   <li><b>Exact double-precision nanosecond cadence</b>: avoids integer-division drift at non-divisor
 *       refresh rates (e.g. 144 Hz, 165 Hz, 240 Hz, 360 Hz).</li>
 *   <li><b>Fast-attack / slow-decay adaptive park-overshoot estimator</b>: measures actual OS
 *       {@code LockSupport.parkNanos} wakeup overshoot and widens the spin reserve immediately when
 *       the OS timer granularity degrades (e.g. Windows 15.6 ms vs 0.5 ms timer ticks), then gently
 *       relaxes back down to save CPU when wakeup precision improves.</li>
 *   <li><b>Bounded interrupt-safe re-park + precision spin tail</b>: if {@code parkNanos} wakes
 *       early (spurious wakeup or Minecraft task unpark permit), re-parks up to 2 bounded times
 *       instead of busy-spinning for 10+ ms, then finishes with {@code Thread.onSpinWait()}.</li>
 *   <li><b>Anti-sawtooth soft phase recovery</b>: on mildly late frames (missed deadline by less
 *       than half a frame period), smoothly damps phase instead of forcing an artificially short
 *       recovery frame that causes long-short micro-stutter oscillation.</li>
 *   <li><b>Single-owner pre-frame / post-swap coordination</b>: guarantees a frame never sleeps
 *       twice and cleanly yields when an external pacer (such as Aurora Client) owns the wait.</li>
 * </ul>
 */
public final class EphemeraFramePacer {
	public enum HookPoint {
		NONE,
		FRAME_HEAD,
		POST_SWAP
	}

	/** Injectable clock/wait seam for deterministic unit testing. */
	public interface WaitOps {
		long nanoTime();
		void parkNanos(long nanos);
		void onSpinWait();
		boolean isInterrupted();
	}

	private static final WaitOps SYSTEM_OPS = new WaitOps() {
		@Override public long nanoTime() { return System.nanoTime(); }
		@Override public void parkNanos(long nanos) { LockSupport.parkNanos(nanos); }
		@Override public void onSpinWait() { Thread.onSpinWait(); }
		@Override public boolean isInterrupted() { return Thread.currentThread().isInterrupted(); }
	};

	private static final long MIN_RESERVE_NS = 200_000L;      // 200 us minimum spin tail
	private static final long MAX_RESERVE_NS = 3_500_000L;    // 3.5 ms safety cap on overshoot reserve
	private static final long REPARK_EXTRA_MARGIN_NS = 750_000L;
	private static final int MAX_REPARK_RETRIES = 2;

	private final WaitOps ops;

	private int activeFps;
	private HookPoint activeOwner = HookPoint.NONE;
	private double nextDeadlineNs;
	private boolean headWaitedThisFrame;

	// Fast-attack / slow-decay park overshoot estimate (nanoseconds).
	private double overshootEstimateNs = 350_000.0;
	private long lastWaitDurationNs;
	private long totalWaits;
	private long totalOversleeps;

	public EphemeraFramePacer() {
		this(SYSTEM_OPS);
	}

	public EphemeraFramePacer(WaitOps ops) {
		this.ops = ops;
	}

	public void reset() {
		activeFps = 0;
		activeOwner = HookPoint.NONE;
		nextDeadlineNs = 0.0;
		headWaitedThisFrame = false;
	}

	/**
	 * Runs at frame head ({@code Minecraft.runTick} HEAD). When {@code lowLatency} is enabled and
	 * {@code targetFps} is active ({@code 1..999}), paces here before input/tick/render.
	 *
	 * @return nanoseconds spent waiting (0 if arming, late, or inactive)
	 */
	public long paceFrameHead(int targetFps, boolean lowLatency, int configuredSpinTailMicros) {
		headWaitedThisFrame = false;
		if (targetFps <= 0 || !lowLatency) {
			if (activeOwner == HookPoint.FRAME_HEAD) {
				reset();
			}
			return 0L;
		}
		long deadline = advanceDeadline(HookPoint.FRAME_HEAD, targetFps, ops.nanoTime());
		if (deadline == 0L) {
			return 0L;
		}
		headWaitedThisFrame = true;
		return waitUntil(deadline, configuredSpinTailMicros);
	}

	/**
	 * Runs at post-swap ({@code FramerateLimiter.limitDisplayFPS}).
	 *
	 * @return {@code true} if Ephemera handled (or already handled at frame-head) this frame's wait
	 *         so vanilla's {@code FramerateLimiter} should be cancelled
	 */
	public boolean pacePostSwap(int targetFps, int configuredSpinTailMicros) {
		if (activeOwner == HookPoint.FRAME_HEAD) {
			if (targetFps > 0 && activeFps <= targetFps) {
				// Keep armed deadline intact when headWaitedThisFrame is false (frame 1 arming).
				return headWaitedThisFrame;
			}
			// A tighter native restriction appeared mid-frame (e.g. minimized); let it take effect.
			reset();
			return false;
		}
		if (targetFps <= 0) {
			reset();
			return false;
		}
		long deadline = advanceDeadline(HookPoint.POST_SWAP, targetFps, ops.nanoTime());
		if (deadline == 0L) {
			// Arming or recovering from a big hitch: already anchored, cancel vanilla to avoid double-drift.
			return true;
		}
		waitUntil(deadline, configuredSpinTailMicros);
		return true;
	}

	/**
	 * Computes the next deadline in nanoseconds, returning {@code 0L} when arming or re-anchoring
	 * after a hitch ({@code > 0.75 * period}).
	 */
	long advanceDeadline(HookPoint owner, int fps, long nowNs) {
		double periodNs = 1_000_000_000.0 / fps;
		if (owner != activeOwner || fps != activeFps) {
			activeOwner = owner;
			activeFps = fps;
			nextDeadlineNs = nowNs + periodNs;
			return 0L;
		}
		if (nextDeadlineNs == 0.0 || nowNs > nextDeadlineNs + periodNs * 0.75) {
			// First frame or genuine hitch: re-anchor cleanly without burst catch-up.
			nextDeadlineNs = nowNs + periodNs;
			return 0L;
		}
		long target = Math.round(nextDeadlineNs);
		if (nowNs > target) {
			// Mildly late frame (0 < lateness <= 0.75 * period): damp phase smoothly to prevent
			// long-short sawtooth oscillation while keeping average framerate steady.
			double lateness = nowNs - nextDeadlineNs;
			nextDeadlineNs = nextDeadlineNs + periodNs + lateness * 0.5;
		} else {
			nextDeadlineNs += periodNs;
		}
		return target;
	}

	/**
	 * Waits until {@code deadlineNs} using adaptive park + bounded re-park + precision spin tail.
	 */
	public long waitUntil(long deadlineNs, int configuredSpinTailMicros) {
		long startNs = ops.nanoTime();
		long remaining = deadlineNs - startNs;
		if (remaining <= 0L) {
			lastWaitDurationNs = 0L;
			return 0L;
		}

		long baseSpinNs = Math.max(MIN_RESERVE_NS, configuredSpinTailMicros * 1_000L);
		long reserveNs = effectiveReserveNs(baseSpinNs);

		int parks = 0;
		long now = startNs;
		while (remaining > reserveNs && parks <= MAX_REPARK_RETRIES) {
			if (parks > 0 && (ops.isInterrupted() || remaining <= reserveNs + REPARK_EXTRA_MARGIN_NS)) {
				break;
			}
			long parkRequest = remaining - reserveNs;
			if (parkRequest <= 0L) {
				break;
			}
			long beforePark = now;
			ops.parkNanos(parkRequest);
			now = ops.nanoTime();
			long actualPark = now - beforePark;
			observeParkOvershoot(parkRequest, actualPark);
			parks++;
			remaining = deadlineNs - now;
			reserveNs = effectiveReserveNs(baseSpinNs);
		}

		while (now < deadlineNs) {
			ops.onSpinWait();
			now = ops.nanoTime();
		}

		totalWaits++;
		if (now - deadlineNs > 250_000L) {
			totalOversleeps++;
		}
		lastWaitDurationNs = Math.max(0L, now - startNs);
		return lastWaitDurationNs;
	}

	/**
	 * Fast-attack / slow-decay overshoot estimator:
	 * positive overshoot (OS woke us late) attacks at 35% so jitter spikes adapt in 1-2 frames;
	 * clean/early wakes decay slowly at 3% so we don't oscillate back into oversleeping.
	 */
	public void observeParkOvershoot(long requestedNanos, long actualNanos) {
		if (requestedNanos <= 0L || actualNanos < 0L) {
			return;
		}
		long overshoot = actualNanos - requestedNanos;
		if (overshoot > 0L) {
			long clamped = Math.min(overshoot, MAX_RESERVE_NS);
			if (clamped > overshootEstimateNs) {
				overshootEstimateNs = overshootEstimateNs * 0.65 + clamped * 0.35;
			} else {
				overshootEstimateNs = overshootEstimateNs * 0.94 + clamped * 0.06;
			}
		} else {
			// Early or exact wake: gently relax reserve toward floor.
			overshootEstimateNs = Math.max(100_000.0, overshootEstimateNs * 0.97);
		}
	}

	public long effectiveReserveNs(long baseSpinNs) {
		long adaptive = Math.round(overshootEstimateNs * 1.15) + 150_000L;
		long chosen = Math.max(baseSpinNs, adaptive);
		return Math.min(MAX_RESERVE_NS, Math.max(MIN_RESERVE_NS, chosen));
	}

	public int adaptiveOvershootMicros() {
		return (int) Math.round(overshootEstimateNs / 1_000.0);
	}

	public long lastWaitDurationNs() {
		return lastWaitDurationNs;
	}

	public long totalWaits() {
		return totalWaits;
	}

	public long totalOversleeps() {
		return totalOversleeps;
	}

	public HookPoint activeOwner() {
		return activeOwner;
	}

	public int activeFps() {
		return activeFps;
	}
}
