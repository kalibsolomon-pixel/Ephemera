package com.ephemera.render.pacing;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.pacing.EphemeraFramePacer;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.render.backend.VulkanAsyncPresenter;
import net.minecraft.client.Minecraft;

/**
 * Render-thread runtime owner of Ephemera's precision frame pacer, adaptive terrain-upload budget,
 * and cooperative handshake with Aurora Client's frame pacer.
 *
 * <p>Cooperation contract:
 * <ul>
 *   <li>When Aurora Client is installed and actively owning frame waits, Aurora reports its live
 *       effective target FPS and wait metrics through {@code ObjectShare} ({@code ephemera:frame_pacer}).
 *       Ephemera automatically yields blocking sleeps to Aurora (preventing any double-wait) while
 *       using Aurora's exact target FPS and wait timing to budget terrain GPU uploads and coordinate
 *       with {@link VulkanAsyncPresenter}.</li>
 *   <li>When Aurora is absent or its custom pacer is disabled/VANILLA, Ephemera's own
 *       {@link EphemeraFramePacer} steps in automatically with adaptive overshoot compensation,
 *       bounded re-parking, anti-sawtooth phase recovery, and optional low-latency frame-head pacing.</li>
 * </ul>
 */
public final class EphemeraPacerRuntime {
	private static final int VANILLA_UNLIMITED_FPS = 260;

	private static final EphemeraFramePacer PACER = new EphemeraFramePacer();
	private static final FrameBudgetPacer BUDGET = new FrameBudgetPacer();

	private static volatile boolean externalBeforeWaitActive;

	private EphemeraPacerRuntime() {}

	public static EphemeraFramePacer pacer() {
		return PACER;
	}

	public static FrameBudgetPacer budget() {
		return BUDGET;
	}

	/**
	 * Invoked by Aurora Client at {@code runTick} HEAD (priority 10000, before Ephemera's 10500)
	 * or post-swap to synchronize ownership and effective FPS target with zero allocations.
	 */
	public static void onAuroraReport(boolean auroraOwnsWait, int effectiveTargetFps, long lastWaitNanos) {
		long now = System.nanoTime();
		BUDGET.reportExternalPacer(auroraOwnsWait, effectiveTargetFps, lastWaitNanos, now);
		if (auroraOwnsWait) {
			PACER.reset();
		}
	}

	/**
	 * Invoked by Aurora Client right before entering a blocking pacer sleep so Ephemera records
	 * clean CPU work end and lets any in-flight {@link VulkanAsyncPresenter} job overlap naturally.
	 */
	public static void onAuroraBeforeWait(long plannedWaitNanos) {
		externalBeforeWaitActive = true;
		BUDGET.endFrameWork(System.nanoTime());
	}

	/**
	 * Invoked by Aurora Client after a park/wait completes so Ephemera's shared adaptive OS
	 * overshoot estimator stays synchronized with real park wake jitter.
	 */
	public static void onAuroraAfterPark(long requestedNanos, long actualNanos) {
		externalBeforeWaitActive = false;
		if (requestedNanos > 0L && actualNanos >= 0L) {
			PACER.observeParkOvershoot(requestedNanos, actualNanos);
		}
	}

	/** Exposed to Aurora's {@code ReflexScheduler} so it knows Vulkan present is overlapped off-thread. */
	public static boolean isVulkanAsyncPresentActive() {
		return VulkanAsyncPresenter.isEnabled();
	}

	/** Exposed to Aurora's {@code FramePacer} so HYBRID/adaptive paths can reuse measured OS overshoot. */
	public static int recommendedOvershootMicros() {
		return PACER.adaptiveOvershootMicros();
	}

	/**
	 * Called at {@code Minecraft.runTick} HEAD (priority 10500, immediately after Aurora's 10000).
	 */
	public static void onFrameHead(Minecraft mc) {
		EphemeraConfig cfg = EphemeraCommon.config();
		long now = System.nanoTime();
		int nativeLimit = mc.getFramerateLimitTracker().getFramerateLimit();
		int activeNative = (nativeLimit > 0 && nativeLimit < VANILLA_UNLIMITED_FPS) ? nativeLimit : 0;

		if (BUDGET.isExternalPacerActive(now)) {
			// Aurora owns the blocking wait; Ephemera only updates frame work & budget state.
			int effective = BUDGET.effectiveTargetFps(now, activeNative);
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.AURORA, effective);
			BUDGET.beginFrameWork(now);
			return;
		}

		if (!cfg.framePacerEnabled) {
			PACER.reset();
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.VANILLA, activeNative);
			BUDGET.beginFrameWork(now);
			return;
		}

		BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, activeNative);
		if (activeNative > 0 && cfg.framePacerLowLatency) {
			// Pace right before input/tick/render for lowest input-to-photon latency.
			PACER.paceFrameHead(activeNative, true, cfg.framePacerSpinTailMicros);
			now = System.nanoTime();
		} else {
			// Ensure frame-head state is cleared if low-latency mode or FPS cap turned off.
			PACER.paceFrameHead(0, false, cfg.framePacerSpinTailMicros);
		}
		BUDGET.beginFrameWork(now);
	}

	/** Called at {@code Minecraft.renderFrame} RETURN to measure pure CPU frame work. */
	public static void onRenderFrameEnd() {
		if (!externalBeforeWaitActive) {
			BUDGET.endFrameWork(System.nanoTime());
		}
		externalBeforeWaitActive = false;
	}

	/**
	 * Called at {@code FramerateLimiter.limitDisplayFPS(int)} HEAD (priority 10500, after Aurora's 10000).
	 *
	 * @return {@code true} if Ephemera handled the wait and vanilla's limiter should be cancelled
	 */
	public static boolean onPostSwapLimit(int fps) {
		EphemeraConfig cfg = EphemeraCommon.config();
		long now = System.nanoTime();
		if (BUDGET.isExternalPacerActive(now) || !cfg.framePacerEnabled) {
			return false;
		}
		int active = (fps > 0 && fps < VANILLA_UNLIMITED_FPS) ? fps : 0;
		if (active <= 0) {
			PACER.reset();
			return false;
		}
		return PACER.pacePostSwap(active, cfg.framePacerSpinTailMicros);
	}

	/** Dynamic per-frame terrain upload budget in nanoseconds for {@code ChunkRenderBatcher}. */
	public static long currentUploadBudgetNs() {
		EphemeraConfig cfg = EphemeraCommon.config();
		return BUDGET.computeUploadBudgetNs(
			cfg.maxSectionUploadsPerFrameMs,
			cfg.framePacerAdaptiveUploadBudget,
			System.nanoTime()
		);
	}

	/** Human-readable status summary for Diagnostics HUD / Settings. */
	public static String statusLine() {
		FrameBudgetPacer.PacerOwner owner = BUDGET.owner();
		int fps = BUDGET.effectiveTargetFps();
		String target = fps > 0 ? (fps + " fps") : "Uncapped";
		return owner.name() + " · " + target
			+ " · jitter " + String.format(java.util.Locale.ROOT, "%.2f ms", BUDGET.jitterMs())
			+ " · OS wake +" + PACER.adaptiveOvershootMicros() + " us";
	}
}
