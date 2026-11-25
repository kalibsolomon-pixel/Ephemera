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
		PRE_PRESENT,
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
	private static final long CAPTURE_MIN_RESERVE_NS = 100_000L; // 100 us floor when yielding to capture encoders
	private static final long MAX_RESERVE_NS = 3_500_000L;    // 3.5 ms safety cap on overshoot reserve
	private static final long REPARK_EXTRA_MARGIN_NS = 750_000L;
	private static final long CAPTURE_REPARK_EXTRA_MARGIN_NS = 150_000L;
	private static final int MAX_REPARK_RETRIES = 2;

	private final WaitOps ops;

	private int activeFps;
	private HookPoint activeOwner = HookPoint.NONE;
	// Deadlines are kept relative to originNs so the double keeps sub-nanosecond precision even
	// after multi-day uptime, where raw System.nanoTime() values exceed the 2^53 exact range.
	private long originNs;
	private double nextDeadlineNs;
	private boolean headWaitedThisFrame;
	private long stage2TargetDeadlineNs;
	private long lastPresentStartNs;

	// Fast-attack / slow-decay CPU work & present-block estimators (nanoseconds) for two-stage predictive pacing.
	private double nonTickWorkHighNs;
	private double tickWorkHighNs;
	private double presentBlockHighNs;

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
		originNs = 0L;
		nextDeadlineNs = 0.0;
		headWaitedThisFrame = false;
		stage2TargetDeadlineNs = 0L;
		lastPresentStartNs = 0L;
	}

	/** Latched Stage 2 present deadline in {@code nanoTime()} ticks for the active frame, or 0 if uncapped/arming. */
	public long latchedTargetPresentNs() {
		return stage2TargetDeadlineNs;
	}

	/**
	 * Records post-present completion after {@code GpuSurface.present()} returns (blocking GL
	 * {@code glfwSwapBuffers} or synchronous present paths) so Stage 1 accounts for driver-side
	 * present time and severe present stalls re-anchor cleanly instead of causing short catch-up frames.
	 */
	public void observePostPresent(long postPresentNs, int targetFps) {
		long start = lastPresentStartNs;
		lastPresentStartNs = 0L;
		if (start <= 0L || postPresentNs <= start) {
			return;
		}
		long blockNs = postPresentNs - start;
		long periodNs = targetFps > 0 ? (1_000_000_000L / targetFps) : 16_666_667L;
		double sample = Math.min(blockNs, periodNs * 0.45);
		if (presentBlockHighNs <= 0.0) {
			presentBlockHighNs = sample;
		} else if (sample > presentBlockHighNs) {
			presentBlockHighNs = presentBlockHighNs * 0.75 + sample * 0.25;
		} else {
			presentBlockHighNs = presentBlockHighNs * 0.985 + sample * 0.015;
		}
		if (activeOwner == HookPoint.PRE_PRESENT && activeFps == targetFps && targetFps > 0
			&& originNs != 0L && nextDeadlineNs > 0.0) {
			double periodD = 1_000_000_000.0 / targetFps;
			double relPost = postPresentNs - originNs;
			if (relPost > nextDeadlineNs + periodD * 0.50) {
				// Present blocked well past the next frame's start: re-anchor cleanly.
				originNs = postPresentNs;
				nextDeadlineNs = periodD;
			}
		}
	}

	/**
	 * Records observed pure CPU work duration (from frame-work start to GPU submit, excluding all
	 * pacer waits), tracked separately for 20 Hz game-tick frames and non-tick frames so Stage 1
	 * wakes early on tick frames and never delays {@code present()}.
	 */
	public void observeCpuWork(long workNs, boolean hadGameTick) {
		if (workNs <= 0L || workNs > 100_000_000L) {
			return;
		}
		long periodNs = activeFps > 0 ? (1_000_000_000L / activeFps) : 16_666_667L;
		// Clamp single outlier spikes (e.g. stop-the-world GC pauses) so they cannot blow up
		// predicted Stage 1 work for seconds; tick frames run at 20 Hz (12x slower than 240 FPS),
		// so tick decay uses a faster per-sample factor to match the non-tick wall-clock half-life.
		double sample = Math.min(workNs, Math.max(periodNs * 0.90, 2_500_000.0));
		if (hadGameTick) {
			if (tickWorkHighNs <= 0.0) {
				tickWorkHighNs = sample;
			} else if (sample > tickWorkHighNs) {
				tickWorkHighNs = tickWorkHighNs * 0.78 + sample * 0.22;
			} else {
				tickWorkHighNs = tickWorkHighNs * 0.90 + sample * 0.10;
			}
		} else {
			if (nonTickWorkHighNs <= 0.0) {
				nonTickWorkHighNs = sample;
			} else if (sample > nonTickWorkHighNs) {
				nonTickWorkHighNs = nonTickWorkHighNs * 0.70 + sample * 0.30;
			} else {
				nonTickWorkHighNs = nonTickWorkHighNs * 0.985 + sample * 0.015;
			}
		}
	}

	/**
	 * Two-stage predictive low-latency Stage 1 at {@code Minecraft.runTick} HEAD.
	 */
	public long paceTwoStageHead(int targetFps, boolean lowLatency, boolean tickLikely,
								 int configuredSpinTailMicros, boolean captureMode) {
		return paceTwoStageHead(targetFps, lowLatency, tickLikely ? 0L : Long.MAX_VALUE, configuredSpinTailMicros, captureMode);
	}

	/**
	 * Two-stage predictive low-latency Stage 1 at {@code Minecraft.runTick} HEAD using exact time
	 * until the next 20 Hz game tick.
	 *
	 * <p>Anchors canonical cadence to {@code GpuSurface.present()} (Stage 2). On non-tick frames,
	 * sleeps until {@code targetPresent - nonTickPredicted}. On frames where a 20 Hz tick will
	 * actually become due before {@code normalWake}, wakes early enough for the tick + render to
	 * finish before {@code targetPresent} while never waking prematurely before the tick boundary.
	 */
	public long paceTwoStageHead(int targetFps, boolean lowLatency, long nsUntilNextTick,
								 int configuredSpinTailMicros, boolean captureMode) {
		headWaitedThisFrame = false;
		if (targetFps <= 0) {
			if (activeOwner == HookPoint.PRE_PRESENT || activeOwner == HookPoint.FRAME_HEAD) {
				reset();
			}
			return 0L;
		}
		if (activeOwner != HookPoint.PRE_PRESENT || activeFps != targetFps) {
			activeOwner = HookPoint.PRE_PRESENT;
			activeFps = targetFps;
			originNs = 0L;
			nextDeadlineNs = 0.0;
			stage2TargetDeadlineNs = 0L;
			return 0L;
		}
		if (originNs == 0L || nextDeadlineNs == 0.0) {
			stage2TargetDeadlineNs = 0L;
			return 0L;
		}
		long nowNs = ops.nanoTime();
		double periodNs = 1_000_000_000.0 / targetFps;
		double relNowNs = nowNs - originNs;
		if (relNowNs > nextDeadlineNs + periodNs * 0.75) {
			// Genuine hitch before frame start: let Stage 2 re-anchor cleanly at present time.
			originNs = 0L;
			nextDeadlineNs = 0.0;
			stage2TargetDeadlineNs = 0L;
			return 0L;
		}
		long targetPresent = originNs + Math.round(nextDeadlineNs);
		stage2TargetDeadlineNs = targetPresent;
		if (!lowLatency) {
			return 0L;
		}
		double nonTickBase = nonTickWorkHighNs > 0.0 ? nonTickWorkHighNs : Math.min(1_200_000.0, periodNs * 0.35);
		double tickBase = Math.max(tickWorkHighNs, nonTickBase);
		// Include observed driver present-block latency (e.g. GL glfwSwapBuffers) capped to 25% of period
		// so blocking swaps do not eat into Stage 2 slack on the following frame.
		long presentPadNs = Math.min(Math.round(periodNs * 0.25), Math.round(presentBlockHighNs * 0.5));
		long marginNs = captureMode
			? 150_000L
			: Math.min(350_000L, Math.max(150_000L, Math.round(periodNs * 0.08)));
		long maxPredictedNs = Math.round(periodNs * 0.85);
		long normalPredictedNs = Math.min(maxPredictedNs, Math.round(nonTickBase) + presentPadNs + marginNs);
		long tickPredictedNs = Math.min(maxPredictedNs, Math.round(tickBase) + presentPadNs + marginNs);

		long normalWake = targetPresent - normalPredictedNs;
		long tickWake = targetPresent - tickPredictedNs;
		long stage1Deadline = normalWake;
		if (nsUntilNextTick <= 0L) {
			stage1Deadline = tickWake;
		} else if (nsUntilNextTick != Long.MAX_VALUE) {
			long tickDueNs = nowNs + nsUntilNextTick;
			// Only wake early if the 20 Hz tick will actually become due by normalWake (+1 ms for Util.getMillis() quantization),
			// and never wake earlier than tickDueNs + 0.5 ms so advanceGameTime() actually executes the tick on this frame.
			if (tickDueNs <= normalWake + 1_000_000L) {
				stage1Deadline = Math.min(normalWake, Math.max(tickDueNs + 500_000L, tickWake));
			}
		}
		if (stage1Deadline <= nowNs) {
			return 0L;
		}
		headWaitedThisFrame = true;
		return waitUntil(stage1Deadline, configuredSpinTailMicros, captureMode, Math.round(periodNs));
	}

	/**
	 * Two-stage Stage 2 immediately AFTER {@code CommandEncoder.submit()} and BEFORE
	 * {@code GpuSurface.present()}. Aligns present calls to exact nanosecond cadence across both
	 * normal and 20 Hz tick frames while overlapping GPU command execution during the residual wait.
	 *
	 * @return {@code true} if Ephemera handled this frame's pacing
	 */
	public boolean paceTwoStagePrePresent(int targetFps, int configuredSpinTailMicros, boolean captureMode) {
		if (targetFps <= 0) {
			if (activeOwner == HookPoint.PRE_PRESENT) {
				reset();
			}
			return false;
		}
		long nowNs = ops.nanoTime();
		double periodNs = 1_000_000_000.0 / targetFps;
		if (activeOwner != HookPoint.PRE_PRESENT || activeFps != targetFps || originNs == 0L || nextDeadlineNs == 0.0) {
			activeOwner = HookPoint.PRE_PRESENT;
			activeFps = targetFps;
			originNs = nowNs;
			nextDeadlineNs = periodNs;
			stage2TargetDeadlineNs = 0L;
			return true;
		}
		long target = stage2TargetDeadlineNs != 0L ? stage2TargetDeadlineNs : (originNs + Math.round(nextDeadlineNs));
		stage2TargetDeadlineNs = 0L;
		double relNowNs = nowNs - originNs;
		if (relNowNs > nextDeadlineNs + periodNs * 0.75) {
			// Genuine hitch during tick/render: re-anchor cleanly at present without burst catch-up.
			originNs = nowNs;
			nextDeadlineNs = periodNs;
			return true;
		}
		if (nowNs > target) {
			// Mildly late frame: shift next deadline forward by 85% of lateness to eliminate
			// short-after-long sawtooth compression while preserving average frame rate.
			double lateness = relNowNs - nextDeadlineNs;
			nextDeadlineNs = nextDeadlineNs + periodNs + lateness * 0.85;
			lastPresentStartNs = nowNs;
			return true;
		}
		waitUntil(target, configuredSpinTailMicros, captureMode, Math.round(periodNs));
		long afterWaitNs = ops.nanoTime();
		lastPresentStartNs = afterWaitNs;
		long wakeOvershoot = afterWaitNs - target;
		if (wakeOvershoot > 200_000L && wakeOvershoot < periodNs * 0.75) {
			// Damp mild OS timer wake overshoot so the next interval is not artificially compressed.
			nextDeadlineNs = nextDeadlineNs + periodNs + wakeOvershoot * 0.50;
		} else if (wakeOvershoot >= periodNs * 0.75) {
			originNs = afterWaitNs;
			nextDeadlineNs = periodNs;
		} else {
			nextDeadlineNs += periodNs;
		}
		return true;
	}

	public double predictedNonTickWorkMs() {
		return nonTickWorkHighNs / 1_000_000.0;
	}

	public double predictedTickWorkMs() {
		return tickWorkHighNs / 1_000_000.0;
	}

	/**
	 * Runs at frame head ({@code Minecraft.runTick} HEAD). When {@code lowLatency} is enabled and
	 * {@code targetFps} is active ({@code 1..999}), paces here before input/tick/render.
	 *
	 * @return nanoseconds spent waiting (0 if arming, late, or inactive)
	 */
	public long paceFrameHead(int targetFps, boolean lowLatency, int configuredSpinTailMicros) {
		return paceFrameHead(targetFps, lowLatency, configuredSpinTailMicros, false);
	}

	/**
	 * Runs at frame head ({@code Minecraft.runTick} HEAD). When {@code lowLatency} is enabled and
	 * {@code targetFps} is active ({@code 1..999}), paces here before input/tick/render.
	 *
	 * @param captureMode when true, enforces {@code configuredSpinTailMicros} as an upper bound on
	 *                    the busy-spin reserve so idle frame time parks and yields CPU to encoders
	 * @return nanoseconds spent waiting (0 if arming, late, or inactive)
	 */
	public long paceFrameHead(int targetFps, boolean lowLatency, int configuredSpinTailMicros, boolean captureMode) {
		headWaitedThisFrame = false;
		if (targetFps <= 0 || !lowLatency) {
			if (activeOwner == HookPoint.FRAME_HEAD || activeOwner == HookPoint.PRE_PRESENT) {
				reset();
			}
			return 0L;
		}
		long deadline = advanceDeadline(HookPoint.FRAME_HEAD, targetFps, ops.nanoTime());
		if (deadline == 0L) {
			return 0L;
		}
		headWaitedThisFrame = true;
		return waitUntil(deadline, configuredSpinTailMicros, captureMode);
	}

	/**
	 * Runs at post-swap ({@code FramerateLimiter.limitDisplayFPS}).
	 *
	 * @return {@code true} if Ephemera handled (or already handled at frame-head) this frame's wait
	 *         so vanilla's {@code FramerateLimiter} should be cancelled
	 */
	public boolean pacePostSwap(int targetFps, int configuredSpinTailMicros) {
		return pacePostSwap(targetFps, configuredSpinTailMicros, false);
	}

	public boolean pacePostSwap(int targetFps, int configuredSpinTailMicros, boolean captureMode) {
		if (activeOwner == HookPoint.FRAME_HEAD || activeOwner == HookPoint.PRE_PRESENT) {
			if (targetFps > 0 && activeFps <= targetFps) {
				// Frame-head or pre-present owns this frame's cadence even when it only armed or
				// re-anchored after a hitch; letting vanilla sleep here would double-wait.
				return true;
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
		waitUntil(deadline, configuredSpinTailMicros, captureMode, 1_000_000_000L / targetFps);
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
			originNs = nowNs;
			nextDeadlineNs = periodNs;
			return 0L;
		}
		double relNowNs = nowNs - originNs;
		if (nextDeadlineNs == 0.0 || relNowNs > nextDeadlineNs + periodNs * 0.75) {
			// First frame or genuine hitch: re-anchor cleanly without burst catch-up.
			originNs = nowNs;
			nextDeadlineNs = periodNs;
			return 0L;
		}
		long target = originNs + Math.round(nextDeadlineNs);
		if (nowNs > target) {
			// Mildly late frame (0 < lateness <= 0.75 * period): damp phase smoothly to prevent
			// long-short sawtooth oscillation while keeping average framerate steady.
			double lateness = relNowNs - nextDeadlineNs;
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
		return waitUntil(deadlineNs, configuredSpinTailMicros, false);
	}

	/**
	 * Waits until {@code deadlineNs} using adaptive park + bounded re-park + precision spin tail.
	 * In {@code captureMode}, the reserve is capped at {@code configuredSpinTailMicros} so OS wake
	 * overshoot spikes never expand busy-spinning at the expense of capture encoders.
	 */
	public long waitUntil(long deadlineNs, int configuredSpinTailMicros, boolean captureMode) {
		return waitUntil(deadlineNs, configuredSpinTailMicros, captureMode, 0L);
	}

	long waitUntil(long deadlineNs, int configuredSpinTailMicros, boolean captureMode, long periodNs) {
		long startNs = ops.nanoTime();
		long remaining = deadlineNs - startNs;
		if (remaining <= 0L) {
			lastWaitDurationNs = 0L;
			return 0L;
		}

		long minReserve = captureMode ? CAPTURE_MIN_RESERVE_NS : MIN_RESERVE_NS;
		long baseSpinNs = Math.max(minReserve, configuredSpinTailMicros * 1_000L);
		long reserveNs = effectiveReserveNs(baseSpinNs, captureMode, periodNs);
		long reparkMargin = captureMode ? CAPTURE_REPARK_EXTRA_MARGIN_NS : REPARK_EXTRA_MARGIN_NS;

		int parks = 0;
		long now = startNs;
		while (remaining > reserveNs && parks <= MAX_REPARK_RETRIES) {
			if (parks > 0 && (ops.isInterrupted() || remaining <= reserveNs + reparkMargin)) {
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
			reserveNs = effectiveReserveNs(baseSpinNs, captureMode, periodNs);
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
				overshootEstimateNs = overshootEstimateNs * 0.60 + clamped * 0.40;
			} else {
				overshootEstimateNs = overshootEstimateNs * 0.94 + clamped * 0.06;
			}
		} else {
			// Early or exact wake: relax slowly so one quiet burst never drops below OS wake jitter.
			overshootEstimateNs = Math.max(200_000.0, overshootEstimateNs * 0.992);
		}
	}

	public long effectiveReserveNs(long baseSpinNs) {
		return effectiveReserveNs(baseSpinNs, false);
	}

	public long effectiveReserveNs(long baseSpinNs, boolean captureMode) {
		return effectiveReserveNs(baseSpinNs, captureMode, 0L);
	}

	/**
	 * Computes the spin reserve, bounded cleanly against {@code periodNs} when active so high-FPS
	 * caps (300–1000 FPS, where {@code periodNs} is 1.0–3.33 ms) never lock into 100% busy-spinning
	 * after a transient OS preemption spike.
	 */
	public long effectiveReserveNs(long baseSpinNs, boolean captureMode, long periodNs) {
		if (captureMode) {
			long res = Math.min(MAX_RESERVE_NS, Math.max(CAPTURE_MIN_RESERVE_NS, baseSpinNs));
			return periodNs > 0L ? Math.min(res, Math.max(CAPTURE_MIN_RESERVE_NS, (periodNs * 2L) / 5L)) : res;
		}
		long adaptive = Math.round(overshootEstimateNs * 1.25) + 180_000L;
		long chosen = Math.max(baseSpinNs, adaptive);
		long maxForPeriod = periodNs > 0L
			? Math.min(MAX_RESERVE_NS, Math.max(MIN_RESERVE_NS, (periodNs * 3L) / 5L))
			: MAX_RESERVE_NS;
		return Math.min(maxForPeriod, Math.max(MIN_RESERVE_NS, chosen));
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
