package com.ephemera.core.pacing;

/**
 * Pure-Java frame-budget and terrain-upload pacer.
 *
 * <p>Separates pure CPU frame work time from pacer sleep time (whether pacing is owned by Aurora
 * Client or by Ephemera itself), computes rolling frame-time jitter (EWMA mean absolute deviation),
 * and calculates a dynamic per-frame terrain upload budget so chunk streaming and Vulkan mesh
 * uploads never push frames past their pacing deadline or tank 1% / 0.1% low FPS.
 */
public final class FrameBudgetPacer {
	public enum PacerOwner {
		VANILLA,
		EPHEMERA,
		AURORA
	}

	private static final long MIN_UPLOAD_BUDGET_NS = 500_000L;       // 0.5 ms floor
	private static final long MAX_SAMPLE_NS = 250_000_000L;          // ignore pause/loading spikes > 250 ms
	private static final long EXTERNAL_STALE_NS = 2_000_000_000L;    // 2 s timeout if Aurora stops reporting

	private PacerOwner owner = PacerOwner.VANILLA;
	private int effectiveTargetFps;

	// External (Aurora) coordination state
	private boolean externalOwnsWait;
	private int externalTargetFps;
	private long externalReportNs;
	private long externalLastWaitNs;

	// Frame timing & jitter estimators (nanoseconds)
	private long lastFrameStartNs;
	private long currentFrameWorkStartNs;
	private double ewmaFrameNs;
	private double ewmaWorkNs;
	private double ewmaJitterNs;
	private long throttledUploadCount;
	private long completedFrames;
	private long frameSequence;

	// Unified per-frame chunk-streaming budget shared across section snapshots and Vulkan mesh uploads.
	// Uncapped frames budget ACTUAL streaming work spent (so unrelated CPU work between stages never
	// starves Vulkan mesh uploads); capped frames ALSO enforce a hard display-period wall-clock deadline.
	private static final long UNCAPPED_STREAMING_MIN_BUDGET_NS = 1_000_000L; // 1.0 ms floor so chunk loading stays crisp
	private static final long UNCAPPED_STREAMING_MAX_BUDGET_NS = 2_500_000L; // 2.5 ms ceiling to protect 1%/0.1% lows
	private boolean frameStreamingDeadlineSet;
	private long frameStreamingBudgetNs;
	private long frameStreamingSpentNs;
	private long frameStreamingDeadlineNs;
	private long frameLatchedPresentTargetNs;
	private long lastCadenceRefNs;
	private int frameStreamingItemsDone;
	private int frameStreamingThrottled;
	// Cached wall clock for the per-item budget gates: re-reads System.nanoTime() only after >= 8
	// further admitted items or >= 250 us of recorded streaming work since the last read (delta
	// based — denied items do not move the admission counter, so a modulo test here would
	// re-read the clock on every denied item in a throttled pass), so a ~2000-section extract
	// pass costs a handful of timer reads instead of ~2000 on capped frames. The cache is seeded
	// from the caller-supplied nowNs in resolveStreamingDeadlineNs (both the initial latch and
	// Stage 2 re-entry), which keeps the value fresh across the stage boundary without any extra
	// timer reads. Deadline error is bounded to that same quantum (all gates still enforce the
	// spent-work budget with plain field reads).
	private static final long CLOCK_UNSET = Long.MIN_VALUE;
	private long frameStreamingClockNs = CLOCK_UNSET;
	private long frameStreamingSpentAtClockNs;
	private int frameStreamingItemsAtClock = -1;

	public void reset() {
		lastFrameStartNs = 0L;
		currentFrameWorkStartNs = 0L;
		lastCadenceRefNs = 0L;
		ewmaFrameNs = 0.0;
		ewmaWorkNs = 0.0;
		ewmaJitterNs = 0.0;
		throttledUploadCount = 0L;
		completedFrames = 0L;
		resetFrameStreaming();
	}

	private void resetFrameStreaming() {
		frameStreamingDeadlineSet = false;
		frameStreamingBudgetNs = 0L;
		frameStreamingSpentNs = 0L;
		frameStreamingDeadlineNs = Long.MAX_VALUE;
		frameLatchedPresentTargetNs = 0L;
		frameStreamingItemsDone = 0;
		frameStreamingThrottled = 0;
		frameStreamingClockNs = CLOCK_UNSET;
		frameStreamingSpentAtClockNs = 0L;
		frameStreamingItemsAtClock = -1;
	}

	/**
	 * Latches the active frame's canonical Stage 2 present deadline so capped chunk streaming
	 * never budgets work past {@code targetPresentNs - safetyCushionNs}.
	 */
	public void setLatchedPresentTargetNs(long targetPresentNs) {
		this.frameLatchedPresentTargetNs = targetPresentNs;
	}

	/**
	 * Records canonical present-to-present cadence timestamps so {@link #jitterMs()} reflects
	 * actual presentation stability rather than intentional Stage 1 tick-wake shifts.
	 */
	public void observeCadenceTimestamp(long presentNs) {
		if (lastCadenceRefNs != 0L) {
			long dt = presentNs - lastCadenceRefNs;
			if (dt > 0L && dt <= MAX_SAMPLE_NS) {
				if (ewmaFrameNs == 0.0) {
					ewmaFrameNs = dt;
					ewmaJitterNs = 0.0;
				} else {
					double diff = Math.abs(dt - ewmaFrameNs);
					ewmaFrameNs = ewmaFrameNs * 0.90 + dt * 0.10;
					ewmaJitterNs = ewmaJitterNs * 0.88 + diff * 0.12;
				}
			}
		}
		lastCadenceRefNs = presentNs;
	}

	/** Remaining streaming headroom in nanoseconds for admission checks (always >= 0). */
	public long remainingStreamingHeadroomNs(long nowNs) {
		if (!frameStreamingDeadlineSet) {
			return Long.MAX_VALUE;
		}
		long bySpent = Math.max(0L, frameStreamingBudgetNs - frameStreamingSpentNs);
		if (frameStreamingDeadlineNs == Long.MAX_VALUE) {
			return bySpent;
		}
		return Math.min(bySpent, Math.max(0L, frameStreamingDeadlineNs - nowNs));
	}

	/** Called by Aurora Client via the {@code ephemera:frame_pacer} ObjectShare bridge at frame head. */
	public void reportExternalPacer(boolean ownsWait, int targetFps, long waitDurationNs, long nowNs) {
		this.externalOwnsWait = ownsWait;
		this.externalTargetFps = Math.max(0, targetFps);
		this.externalLastWaitNs = Math.max(0L, waitDurationNs);
		this.externalReportNs = nowNs;
		if (ownsWait) {
			this.owner = PacerOwner.AURORA;
			this.effectiveTargetFps = this.externalTargetFps;
		}
	}

	/** True when Aurora Client recently reported that it owns frame-sleep pacing. */
	public boolean isExternalPacerActive(long nowNs) {
		return externalOwnsWait && externalReportNs != 0L && (nowNs - externalReportNs) <= EXTERNAL_STALE_NS;
	}

	/** Returns the active target FPS known from either Aurora or Ephemera (0 if uncapped). */
	public int effectiveTargetFps(long nowNs, int localTargetFps) {
		if (isExternalPacerActive(nowNs)) {
			if (externalTargetFps > 0 && localTargetFps > 0) {
				return Math.min(externalTargetFps, localTargetFps);
			}
			return externalTargetFps > 0 ? externalTargetFps : localTargetFps;
		}
		return Math.max(0, localTargetFps);
	}

	public void setActiveOwner(PacerOwner owner, int targetFps) {
		this.owner = owner;
		this.effectiveTargetFps = Math.max(0, targetFps);
	}

	/**
	 * Marks the beginning of active frame work (immediately after any frame-head pacer wait).
	 */
	public void beginFrameWork(long nowNs) {
		// When present-anchored cadence timestamps are being supplied (two-stage pacing), let
		// observeCadenceTimestamp() drive ewmaFrameNs / ewmaJitterNs so intentional Stage 1 early
		// wakeups on 20 Hz tick frames never inflate jitter estimates.
		if (lastCadenceRefNs == 0L && lastFrameStartNs != 0L) {
			long dt = nowNs - lastFrameStartNs;
			if (dt > 0L && dt <= MAX_SAMPLE_NS) {
				if (ewmaFrameNs == 0.0) {
					ewmaFrameNs = dt;
					ewmaJitterNs = 0.0;
				} else {
					double diff = Math.abs(dt - ewmaFrameNs);
					ewmaFrameNs = ewmaFrameNs * 0.90 + dt * 0.10;
					ewmaJitterNs = ewmaJitterNs * 0.88 + diff * 0.12;
				}
			}
		}
		lastFrameStartNs = nowNs;
		currentFrameWorkStartNs = nowNs;
		// Unconditional frame sequence for per-frame stamping (completedFrames only advances when
		// work fits the sample cap, so it can stall across > 250 ms world-load hitch frames).
		frameSequence++;
		resetFrameStreaming();
	}

	/**
	 * Marks the end of active CPU frame work (before post-swap wait / after renderFrame).
	 */
	public void endFrameWork(long nowNs) {
		if (currentFrameWorkStartNs == 0L) {
			return;
		}
		long work = nowNs - currentFrameWorkStartNs;
		if (work > 0L && work <= MAX_SAMPLE_NS) {
			if (ewmaWorkNs == 0.0) {
				ewmaWorkNs = work;
			} else {
				ewmaWorkNs = ewmaWorkNs * 0.85 + work * 0.15;
			}
			completedFrames++;
		}
		currentFrameWorkStartNs = 0L;
	}

	/**
	 * Computes the dynamic per-frame terrain streaming budget in nanoseconds.
	 *
	 * <p>Respects {@code configuredMaxMs}, and when an FPS cap is active (or recent frame jitter is
	 * elevated), automatically scales the budget to fit inside the remaining frame headroom so
	 * chunk streaming does not cause deadline misses. When capped, never inflates remaining
	 * headroom above what is actually left in the frame period.
	 */
	public long computeUploadBudgetNs(int configuredMaxMs, boolean adaptiveEnabled, long nowNs) {
		long configuredNs = Math.max(1, configuredMaxMs) * 1_000_000L;
		if (!adaptiveEnabled) {
			return configuredNs;
		}
		long budget = configuredNs;

		// 1. Respect target frame period headroom when FPS-capped (by Aurora or Ephemera).
		int targetFps = effectiveTargetFps;
		if (targetFps > 0) {
			long periodNs = 1_000_000_000L / targetFps;
			long elapsedWorkNs = currentFrameWorkStartNs != 0L ? Math.max(0L, nowNs - currentFrameWorkStartNs) : 0L;
			long baselineWorkNs = Math.max(elapsedWorkNs, Math.round(ewmaWorkNs));
			// Reserve 15% of frame period (clamped to [0.4 ms, 1.5 ms]) for remaining draw + swap + spin.
			long safetyCushionNs = Math.min(1_500_000L, Math.max(400_000L, periodNs / 6L));
			long headroomNs = Math.max(0L, periodNs - baselineWorkNs - safetyCushionNs);
			long capByPeriod = Math.min(periodNs / 3L, headroomNs);
			budget = Math.min(budget, capByPeriod);
			// 2. If recent jitter is high (> 1.5 ms MAD), gently tighten non-urgent streaming by 35%.
			if (ewmaJitterNs > 1_500_000.0) {
				budget = (budget * 65L) / 100L;
			}
			return Math.max(0L, budget);
		} else if (ewmaWorkNs > 0.0) {
			// Uncapped: cap streaming to 35% of typical frame work so chunk streaming never doubles frame time.
			long smoothCap = Math.max(MIN_UPLOAD_BUDGET_NS, Math.round(ewmaWorkNs * 0.35));
			budget = Math.min(budget, smoothCap);
		}

		// 2. If recent jitter is high (> 1.5 ms MAD), gently tighten non-urgent uploads by 35%.
		if (ewmaJitterNs > 1_500_000.0) {
			budget = Math.max(MIN_UPLOAD_BUDGET_NS, (budget * 65L) / 100L);
		}

		return Math.max(MIN_UPLOAD_BUDGET_NS, budget);
	}

	/**
	 * Latches ONE shared chunk-streaming budget for the active frame so section snapshots and
	 * Vulkan mesh uploads spend from the same budget instead of stacking independent budgets.
	 *
	 * <p>On uncapped frames, returns {@code Long.MAX_VALUE} for the wall-clock hard deadline and
	 * gates both stages by actual cumulative streaming work ({@link #frameStreamingSpentNs}) against
	 * {@link #frameStreamingBudgetNs} so unrelated CPU work between stages never starves GPU uploads.
	 * On FPS-capped frames, ALSO latches a hard wall-clock deadline inside the frame period.
	 *
	 * @param maxBudgetNs optional ceiling (e.g. capture-mode clamp), or {@code Long.MAX_VALUE}
	 * @return hard wall-clock {@code System.nanoTime()} deadline for capped frames, or {@code Long.MAX_VALUE}
	 */
	public long resolveStreamingDeadlineNs(int configuredMaxMs, boolean adaptiveEnabled, long maxBudgetNs, long nowNs) {
		if (!adaptiveEnabled) {
			return Long.MAX_VALUE;
		}
		if (!frameStreamingDeadlineSet) {
			// Seed the cached wall clock from the caller-supplied timestamp (free — no timer read)
			// so Stage 1's gates start from this frame's real time instead of a stale value.
			frameStreamingClockNs = nowNs;
			frameStreamingItemsAtClock = frameStreamingItemsDone;
			frameStreamingSpentAtClockNs = frameStreamingSpentNs;
			int targetFps = effectiveTargetFps;
			long computed;
			if (targetFps > 0) {
				computed = computeUploadBudgetNs(configuredMaxMs, true, nowNs);
			} else {
				long configuredNs = Math.max(1, configuredMaxMs) * 1_000_000L;
				// Scale smoothly with typical frame work instead of forcing a 3 ms floor on sub-ms frames.
				long scaled = ewmaWorkNs > 0.0
					? Math.max(UNCAPPED_STREAMING_MIN_BUDGET_NS,
						Math.min(UNCAPPED_STREAMING_MAX_BUDGET_NS, Math.round(ewmaWorkNs * 0.50)))
					: Math.min(configuredNs, UNCAPPED_STREAMING_MAX_BUDGET_NS);
				computed = Math.min(configuredNs, scaled);
				if (ewmaJitterNs > 1_200_000.0) {
					computed = Math.max(MIN_UPLOAD_BUDGET_NS, (computed * 65L) / 100L);
				}
			}
			long clamped = Math.max(0L, Math.min(computed, maxBudgetNs));
			frameStreamingBudgetNs = clamped;
			if (targetFps > 0) {
				long periodNs = 1_000_000_000L / targetFps;
				long safetyCushionNs = Math.min(1_500_000L, Math.max(400_000L, periodNs / 6L));
				long byBudget = nowNs + clamped;
				long byPeriod;
				if (frameLatchedPresentTargetNs > 0L) {
					// Anchor directly to Stage 2's exact present deadline so streaming never overruns present().
					byPeriod = Math.max(nowNs, frameLatchedPresentTargetNs - safetyCushionNs);
				} else if (currentFrameWorkStartNs != 0L) {
					byPeriod = Math.max(nowNs, currentFrameWorkStartNs + periodNs - safetyCushionNs);
				} else {
					byPeriod = byBudget;
				}
				frameStreamingDeadlineNs = Math.min(byBudget, byPeriod);
			} else {
				frameStreamingDeadlineNs = Long.MAX_VALUE;
			}
			frameStreamingDeadlineSet = true;
		} else {
			// Stage 2 re-entry (Vulkan mesh upload drain): re-seed from the caller's fresh
			// timestamp so gates never admit from a clock left over before the frame's render work.
			frameStreamingClockNs = nowNs;
			frameStreamingItemsAtClock = frameStreamingItemsDone;
			frameStreamingSpentAtClockNs = frameStreamingSpentNs;
		}
		return frameStreamingDeadlineNs;
	}

	/**
	 * Cached wall-clock check against the frame's hard streaming deadline. Re-reads
	 * {@code System.nanoTime()} only after >= 8 further admissions or >= 250 us of recorded
	 * streaming work since the last read, so a ~2000-section extract pass on a capped frame costs
	 * a handful of timer reads instead of ~2000, with admission error bounded to that same
	 * quantum. The deadline is frame-fixed and the clock monotonic, so an expired cached read
	 * short-circuits as expired. Always returns {@code false} when no hard deadline is set
	 * (uncapped frames: zero timer reads).
	 */
	private boolean pastHardDeadline() {
		long hardDeadline = frameStreamingDeadlineNs;
		if (hardDeadline == Long.MAX_VALUE) {
			return false;
		}
		long clock = frameStreamingClockNs;
		if (clock != CLOCK_UNSET && clock >= hardDeadline) {
			return true;
		}
		long spent = frameStreamingSpentNs;
		if (clock == CLOCK_UNSET
			|| frameStreamingItemsDone - frameStreamingItemsAtClock >= 8
			|| spent - frameStreamingSpentAtClockNs >= 250_000L) {
			clock = System.nanoTime();
			frameStreamingClockNs = clock;
			frameStreamingItemsAtClock = frameStreamingItemsDone;
			frameStreamingSpentAtClockNs = spent;
		}
		return clock >= hardDeadline;
	}

	/**
	 * Fast check for whether the shared streaming budget still has unspent headroom for the
	 * snapshot stage (stopping at 75% of the shared budget when {@code reserveGpuUploadSlice} is
	 * true so Vulkan mesh uploads later in the frame are never starved by snapshot bursts).
	 */
	public boolean hasSnapshotStreamingHeadroom(boolean reserveGpuUploadSlice) {
		if (!frameStreamingDeadlineSet) {
			return true;
		}
		long cap = reserveGpuUploadSlice ? (frameStreamingBudgetNs * 3L) / 4L : frameStreamingBudgetNs;
		if (frameStreamingSpentNs >= cap) {
			return false;
		}
		return !pastHardDeadline();
	}

	/**
	 * Snapshot-stage gate respecting both the shared budget (optionally leaving 25% reserved for
	 * Vulkan mesh uploads within the same single frame budget) and the nearby priority floor.
	 */
	public boolean allowSnapshotStreamingWork(int priorityFloor, boolean reserveGpuUploadSlice) {
		if (!frameStreamingDeadlineSet) {
			return true;
		}
		if (pastHardDeadline()) {
			// On FPS-capped frames whose deadline has already elapsed, allow at most 1 item so streaming
			// never starves without letting priorityFloor > 1 pile extra latency onto a late frame.
			if (frameStreamingItemsDone == 0) {
				return true;
			}
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		int minItems = Math.max(1, priorityFloor);
		if (frameStreamingItemsDone < minItems) {
			return true;
		}
		long cap = reserveGpuUploadSlice ? (frameStreamingBudgetNs * 3L) / 4L : frameStreamingBudgetNs;
		if (frameStreamingSpentNs >= cap) {
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		return true;
	}

	/**
	 * Fast-path check for whether a streaming stage (section snapshot or Vulkan mesh upload) may
	 * process another non-urgent item under the shared frame budget. Zero {@code System.nanoTime()}
	 * calls on uncapped frames; checks the cached wall-clock hard deadline only on FPS-capped frames.
	 */
	public boolean allowStreamingWork(int priorityFloor) {
		if (!frameStreamingDeadlineSet) {
			return true;
		}
		if (pastHardDeadline()) {
			if (frameStreamingItemsDone == 0) {
				return true;
			}
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		int minItems = Math.max(1, priorityFloor);
		if (frameStreamingItemsDone < minItems) {
			return true;
		}
		if (frameStreamingSpentNs >= frameStreamingBudgetNs) {
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		return true;
	}

	/**
	 * Deterministic-clock check used by unit tests and explicit-timestamp callers.
	 */
	public boolean allowStreamingWork(long nowNs, long deadlineNs, int priorityFloor) {
		if (!frameStreamingDeadlineSet && deadlineNs == Long.MAX_VALUE) {
			return true;
		}
		if (deadlineNs != Long.MAX_VALUE && nowNs >= deadlineNs) {
			if (frameStreamingItemsDone == 0) {
				return true;
			}
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		int minItems = Math.max(1, priorityFloor);
		if (frameStreamingItemsDone < minItems) {
			return true;
		}
		if (frameStreamingDeadlineSet && frameStreamingSpentNs >= frameStreamingBudgetNs) {
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		return true;
	}

	/** Records one completed non-urgent chunk-streaming item and its actual CPU/upload duration. */
	public void recordStreamingWork(long elapsedNs) {
		frameStreamingItemsDone++;
		if (elapsedNs > 0L) {
			frameStreamingSpentNs += elapsedNs;
		}
	}

	/** Records one completed non-urgent chunk-streaming item without an explicit duration. */
	public void recordStreamingWork() {
		frameStreamingItemsDone++;
	}

	public long frameStreamingSpentNs() {
		return frameStreamingSpentNs;
	}

	public int frameStreamingItemsDone() {
		return frameStreamingItemsDone;
	}

	public int frameStreamingThrottled() {
		return frameStreamingThrottled;
	}

	public long frameStreamingBudgetNs() {
		return frameStreamingBudgetNs;
	}

	/**
	 * Returns true if another terrain section may be uploaded this frame. Always allows at least
	 * one section ({@code uploadedSoFar == 0}) so terrain streaming can never starve.
	 */
	public boolean allowSectionUpload(int uploadedSoFar, long uploadElapsedNs, long budgetNs) {
		if (uploadedSoFar <= 0) {
			return true;
		}
		if (uploadElapsedNs < budgetNs) {
			return true;
		}
		throttledUploadCount++;
		return false;
	}

	public PacerOwner owner() {
		return owner;
	}

	public int effectiveTargetFps() {
		return effectiveTargetFps;
	}

	public double jitterMs() {
		return ewmaJitterNs / 1_000_000.0;
	}

	public double meanWorkMs() {
		return ewmaWorkNs / 1_000_000.0;
	}

	public double meanFrameMs() {
		return ewmaFrameNs / 1_000_000.0;
	}

	public long externalLastWaitNs() {
		return externalLastWaitNs;
	}

	public long throttledUploadCount() {
		return throttledUploadCount;
	}

	public long completedFrames() {
		return completedFrames;
	}

	/**
	 * Unconditional per-frame counter that advances once per {@link #beginFrameWork(long)} even
	 * when the frame's measured work exceeds the sample cap (unlike {@link #completedFrames()}).
	 */
	public long frameSequence() {
		return frameSequence;
	}
}
