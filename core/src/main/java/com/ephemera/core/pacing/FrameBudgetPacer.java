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

	public void reset() {
		lastFrameStartNs = 0L;
		currentFrameWorkStartNs = 0L;
		ewmaFrameNs = 0.0;
		ewmaWorkNs = 0.0;
		ewmaJitterNs = 0.0;
		throttledUploadCount = 0L;
		completedFrames = 0L;
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
	 * Computes the dynamic per-frame terrain GPU upload budget in nanoseconds.
	 *
	 * <p>Respects {@code configuredMaxMs}, and when an FPS cap is active (or recent frame jitter is
	 * elevated), automatically scales the budget to fit inside the remaining frame headroom so
	 * chunk streaming does not cause deadline misses.
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
			// Reserve 1.0 ms (or 15% of frame period) safety cushion for remaining draw + swap + pacer spin.
			long safetyCushionNs = Math.min(1_500_000L, Math.max(400_000L, periodNs / 6L));
			long headroomNs = periodNs - baselineWorkNs - safetyCushionNs;
			long capByPeriod = Math.max(MIN_UPLOAD_BUDGET_NS, Math.min(periodNs / 3L, headroomNs));
			budget = Math.min(budget, capByPeriod);
		} else if (ewmaWorkNs > 0.0) {
			// Uncapped: cap uploads to 35% of typical frame work so chunk streaming never doubles frame time.
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
