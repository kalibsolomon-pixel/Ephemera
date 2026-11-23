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

	// Unified per-frame chunk-streaming budget shared across section snapshots and Vulkan mesh uploads.
	// Uncapped frames budget ACTUAL streaming work spent (so unrelated CPU work between stages never
	// starves Vulkan mesh uploads); capped frames ALSO enforce a hard display-period wall-clock deadline.
	private static final long UNCAPPED_STREAMING_BASE_BUDGET_NS = 3_000_000L; // 3.0 ms shared across both stages
	private boolean frameStreamingDeadlineSet;
	private long frameStreamingBudgetNs;
	private long frameStreamingSpentNs;
	private long frameStreamingDeadlineNs;
	private int frameStreamingItemsDone;
	private int frameStreamingThrottled;

	public void reset() {
		lastFrameStartNs = 0L;
		currentFrameWorkStartNs = 0L;
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
		frameStreamingItemsDone = 0;
		frameStreamingThrottled = 0;
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
		if (lastFrameStartNs != 0L) {
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
			int targetFps = effectiveTargetFps;
			long computed;
			if (targetFps > 0) {
				computed = computeUploadBudgetNs(configuredMaxMs, true, nowNs);
			} else {
				long configuredNs = Math.max(1, configuredMaxMs) * 1_000_000L;
				long uncapped = ewmaWorkNs > 0.0
					? Math.max(UNCAPPED_STREAMING_BASE_BUDGET_NS, Math.round(ewmaWorkNs * 0.50))
					: Math.min(configuredNs, UNCAPPED_STREAMING_BASE_BUDGET_NS);
				computed = Math.min(configuredNs, uncapped);
				if (ewmaJitterNs > 1_500_000.0) {
					computed = Math.max(MIN_UPLOAD_BUDGET_NS, (computed * 65L) / 100L);
				}
			}
			long clamped = Math.max(0L, Math.min(computed, maxBudgetNs));
			frameStreamingBudgetNs = clamped;
			if (targetFps > 0) {
				long periodNs = 1_000_000_000L / targetFps;
				long safetyCushionNs = Math.min(1_500_000L, Math.max(400_000L, periodNs / 6L));
				long byBudget = nowNs + clamped;
				long byPeriod = currentFrameWorkStartNs != 0L
					? Math.max(nowNs, currentFrameWorkStartNs + periodNs - safetyCushionNs)
					: byBudget;
				frameStreamingDeadlineNs = Math.min(byBudget, byPeriod);
			} else {
				frameStreamingDeadlineNs = Long.MAX_VALUE;
			}
			frameStreamingDeadlineSet = true;
		}
		return frameStreamingDeadlineNs;
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
		long hardDeadline = frameStreamingDeadlineNs;
		return hardDeadline == Long.MAX_VALUE || System.nanoTime() < hardDeadline;
	}

	/**
	 * Snapshot-stage gate respecting both the shared budget (optionally leaving 25% reserved for
	 * Vulkan mesh uploads within the same single frame budget) and the nearby priority floor.
	 */
	public boolean allowSnapshotStreamingWork(int priorityFloor, boolean reserveGpuUploadSlice) {
		if (!frameStreamingDeadlineSet) {
			return true;
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
		long hardDeadline = frameStreamingDeadlineNs;
		if (hardDeadline != Long.MAX_VALUE && System.nanoTime() >= hardDeadline) {
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		return true;
	}

	/**
	 * Fast-path check for whether a streaming stage (section snapshot or Vulkan mesh upload) may
	 * process another non-urgent item under the shared frame budget. Zero {@code System.nanoTime()}
	 * calls on uncapped frames; checks wall-clock hard deadline only on FPS-capped frames.
	 */
	public boolean allowStreamingWork(int priorityFloor) {
		if (!frameStreamingDeadlineSet) {
			return true;
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
		long hardDeadline = frameStreamingDeadlineNs;
		if (hardDeadline != Long.MAX_VALUE && System.nanoTime() >= hardDeadline) {
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
		int minItems = Math.max(1, priorityFloor);
		if (frameStreamingItemsDone < minItems) {
			return true;
		}
		if (frameStreamingDeadlineSet && frameStreamingSpentNs >= frameStreamingBudgetNs) {
			throttledUploadCount++;
			frameStreamingThrottled++;
			return false;
		}
		if (deadlineNs != Long.MAX_VALUE && nowNs >= deadlineNs) {
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
}
