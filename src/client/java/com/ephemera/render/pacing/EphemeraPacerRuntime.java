package com.ephemera.render.pacing;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.core.pacing.CapturePacingPolicy;
import com.ephemera.core.pacing.EphemeraFramePacer;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.core.pacing.FrameRateLimitPolicy;
import com.ephemera.mixin.client.engine.DeltaTrackerTimerAccessor;
import com.ephemera.platform.RenderPlatform;
import com.ephemera.platform.Win32DisplayRuntime;
import com.ephemera.render.gui.GuiItemBakingManager;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Render-thread runtime owner of Ephemera's two-stage precision frame pacer, adaptive
 * terrain-upload budget, extended FPS limit (up to 1000 fps), and cooperative handshake with
 * Aurora Client's frame pacer.
 *
 * <p>For non-VSync capped frames when {@code MinecraftPrePresentPacingMixin} is applied, Ephemera
 * anchors cadence to {@code GpuSurface.present()} (Stage 2, after {@code CommandEncoder.submit()})
 * while Stage 1 at {@code runTick} HEAD sleeps until {@code targetPresent - predictedWork},
 * waking earlier on predicted 20 Hz game-tick frames. This gives low input-to-photon latency
 * without the 20 Hz tick-interval sawtooth that pure frame-head pacing suffers.
 */
public final class EphemeraPacerRuntime {
	private static final int FALLBACK_REFRESH_RATE = 60;

	private static final EphemeraFramePacer PACER = new EphemeraFramePacer(new EphemeraFramePacer.WaitOps() {
		@Override public long nanoTime() { return System.nanoTime(); }
		@Override public void parkNanos(long nanos) { Win32DisplayRuntime.parkNanos(nanos); }
		// Unroll the pause instruction ~8x between nanoTime() reads: the spin tail is hundreds of
		// microseconds, so deadline precision is unchanged while QPC read traffic drops ~8x.
		@Override public void onSpinWait() {
			Thread.onSpinWait();
			Thread.onSpinWait();
			Thread.onSpinWait();
			Thread.onSpinWait();
			Thread.onSpinWait();
			Thread.onSpinWait();
			Thread.onSpinWait();
			Thread.onSpinWait();
		}
		@Override public boolean isInterrupted() { return Thread.currentThread().isInterrupted(); }
		@Override public boolean preciseWakeSource() { return Win32DisplayRuntime.isHighResWaitTimerActive(); }
	});
	private static final FrameBudgetPacer BUDGET = new FrameBudgetPacer();

	private static volatile boolean externalBeforeWaitActive;
	private static volatile AuroraLimits auroraLimits;
	private static int postSwapTargetWithoutNativeLimiter;
	private static int prePresentTarget;
	private static int postPresentTarget;
	private static int prePresentSpinTailMicros;
	private static boolean prePresentCapture;
	private static int vanillaExtendedFallbackTarget;
	private static long frameWorkStartNs;
	private static boolean frameWorkEnded;
	private static boolean frameHadGameTick;

	private static final long REFRESH_CACHE_TTL_NS = 1_000_000_000L;
	private static int cachedRefreshRate = FALLBACK_REFRESH_RATE;
	private static int cachedWinX = Integer.MIN_VALUE;
	private static int cachedWinY = Integer.MIN_VALUE;
	private static int cachedWinW = -1;
	private static int cachedWinH = -1;
	private static boolean cachedFullscreen;
	private static long cachedRefreshAtNs = Long.MIN_VALUE;

	private record AuroraLimits(IntUnaryOperator cap, BooleanSupplier forceVsyncOff) {}

	private EphemeraPacerRuntime() {}

	public static EphemeraFramePacer pacer() {
		return PACER;
	}

	public static FrameBudgetPacer budget() {
		return BUDGET;
	}

	public static void setAuroraLimits(IntUnaryOperator cap, BooleanSupplier forceVsyncOff) {
		if (MixinHealth.applied().contains("MinecraftFramePacingMixin")
			&& MixinHealth.applied().contains("FramerateLimiterPacingMixin")) {
			auroraLimits = new AuroraLimits(cap, forceVsyncOff);
		}
	}

	public static boolean ownsAuroraPacing() {
		return auroraLimits != null && EphemeraCommon.config().framePacerEnabled
			&& !MixinHealth.hasFailed("MinecraftFramePacingMixin")
			&& !MixinHealth.hasFailed("FramerateLimiterPacingMixin");
	}

	private static boolean isPrePresentHookApplied() {
		return MixinHealth.applied().contains("MinecraftPrePresentPacingMixin")
			&& !MixinHealth.hasFailed("MinecraftPrePresentPacingMixin");
	}

	/**
	 * Invoked by Aurora Client when it still owns the wait, for example with older clients.
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
	 * clean CPU work end and lets any in-flight Vulkan async presenter job overlap naturally.
	 */
	public static void onAuroraBeforeWait(long plannedWaitNanos) {
		externalBeforeWaitActive = true;
		frameWorkEnded = true;
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
		return RenderPlatform.isVulkanAsyncPresentActive();
	}

	/** Exposed to Aurora's {@code FramePacer} so HYBRID/adaptive paths can reuse measured OS overshoot. */
	public static int recommendedOvershootMicros() {
		return PACER.adaptiveOvershootMicros();
	}

	/** Called at {@code Minecraft.tick} HEAD to separate 20 Hz tick frames from non-tick frames. */
	public static void onGameTickRan() {
		frameHadGameTick = true;
	}

	/**
	 * Called at {@code Minecraft.runTick} HEAD before input and render work.
	 */
	public static void onFrameHead(Minecraft mc) {
		GuiItemBakingManager.beginFrame();
		EphemeraConfig cfg = EphemeraCommon.config();
		long now = System.nanoTime();
		int nativeLimit = mc.getFramerateLimitTracker().getFramerateLimit();
		int activeLimit = resolveUserLimit(nativeLimit, cfg);
		boolean managedAurora = ownsAuroraPacing();
		postSwapTargetWithoutNativeLimiter = 0;
		prePresentTarget = 0;
		postPresentTarget = 0;
		vanillaExtendedFallbackTarget = 0;
		frameHadGameTick = false;
		frameWorkEnded = false;

		if (!managedAurora && BUDGET.isExternalPacerActive(now)) {
			// Aurora owns the blocking wait; Ephemera only updates frame work & budget state.
			int effective = BUDGET.effectiveTargetFps(now, activeLimit);
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.AURORA, effective);
			frameWorkStartNs = now;
			BUDGET.beginFrameWork(now);
			return;
		}

		boolean vsync = usesVsync(mc, managedAurora);
		if (!cfg.framePacerEnabled) {
			// Adaptive Frame Pacer is disabled: yield standard caps to vanilla/VSync, while still
			// honoring Ephemera's extended cap (>= 260 fps) via vanilla's FramerateLimiter.
			PACER.reset();
			if (!vsync && nativeLimit >= FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF && activeLimit > 0) {
				vanillaExtendedFallbackTarget = activeLimit;
			}
			int effectiveBudgetFps = vsync
				? (activeLimit > 0 ? Math.min(activeLimit, safeRefreshRate(mc, now)) : safeRefreshRate(mc, now))
				: activeLimit;
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.VANILLA, effectiveBudgetFps);
			frameWorkStartNs = now;
			BUDGET.beginFrameWork(now);
			return;
		}

		int target = resolveTargetFps(nativeLimit, activeLimit, managedAurora);
		boolean captureActive = CaptureOptimizationService.isCaptureActive();
		if (captureActive) {
			target = CapturePacingPolicy.resolveCaptureFpsCap(cfg.captureFpsCapMode, safeRefreshRate(mc, now), target);
		}
		int spinTailMicros = CapturePacingPolicy.effectiveSpinTailMicros(
			cfg.framePacerSpinTailMicros, captureActive, Win32DisplayRuntime.isHighResTimerActive());
		if (vsync) {
			// Never sleep before render or before present under VSync: present already blocks on
			// VBlank, so pre-frame/pre-present waits phase-lock into missed VBlanks.
			int refresh = safeRefreshRate(mc, now);
			PACER.paceFrameHead(0, false, spinTailMicros, captureActive);
			int effectiveBudgetFps = target > 0 ? Math.min(target, refresh) : refresh;
			if (nativeLimit >= FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF && target > 0 && target < refresh) {
				postSwapTargetWithoutNativeLimiter = target;
			}
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, effectiveBudgetFps);
			frameWorkStartNs = now;
			BUDGET.beginFrameWork(now);
			return;
		}

		BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, target);
		if (target > 0 && isPrePresentHookApplied()) {
			long nsUntilTick = nsUntilNextGameTick(mc);
			PACER.paceTwoStageHead(target, cfg.framePacerLowLatency, nsUntilTick, spinTailMicros, captureActive);
			now = System.nanoTime();
			prePresentTarget = target;
			prePresentSpinTailMicros = spinTailMicros;
			prePresentCapture = captureActive;
		} else if (target > 0 && cfg.framePacerLowLatency) {
			// Fallback if pre-present hook stood down: pure frame-head low-latency pacing.
			PACER.paceFrameHead(target, true, spinTailMicros, captureActive);
			now = System.nanoTime();
		} else {
			PACER.paceFrameHead(0, false, spinTailMicros, captureActive);
			if (nativeLimit >= FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF && target > 0) {
				postSwapTargetWithoutNativeLimiter = target;
			}
		}
		frameWorkStartNs = now;
		BUDGET.beginFrameWork(now);
		long latchedPresent = PACER.latchedTargetPresentNs();
		if (latchedPresent > 0L) {
			BUDGET.setLatchedPresentTargetNs(latchedPresent);
		}
	}

	/**
	 * Called immediately AFTER {@code CommandEncoder.submit()} and BEFORE
	 * {@code GpuSurface.present()} inside {@code Minecraft.renderFrame}.
	 */
	public static void onPrePresent() {
		long now = System.nanoTime();
		finishCpuWork(now);
		int target = prePresentTarget;
		prePresentTarget = 0;
		if (target > 0 && EphemeraCommon.config().framePacerEnabled) {
			PACER.paceTwoStagePrePresent(target, prePresentSpinTailMicros, prePresentCapture);
			postPresentTarget = target;
			BUDGET.observeCadenceTimestamp(System.nanoTime());
		}
	}

	/**
	 * Called immediately AFTER {@code GpuSurface.present()} returns so blocking GL swaps or WDDM
	 * present stalls feed back into Stage 1 headroom and hitch recovery.
	 */
	public static void onPostPresent() {
		int target = postPresentTarget;
		postPresentTarget = 0;
		if (target > 0 && EphemeraCommon.config().framePacerEnabled) {
			PACER.observePostPresent(System.nanoTime(), target);
		}
	}

	/** Called at {@code Minecraft.renderFrame} RETURN to measure pure CPU frame work and run any deferred cap. */
	public static void onRenderFrameEnd() {
		long now = System.nanoTime();
		finishCpuWork(now);
		externalBeforeWaitActive = false;

		int preTarget = prePresentTarget;
		prePresentTarget = 0;
		if (preTarget > 0 && EphemeraCommon.config().framePacerEnabled) {
			// Surface was not acquired this frame (e.g., minimized/occluded); still honor cadence.
			PACER.paceTwoStagePrePresent(preTarget, prePresentSpinTailMicros, prePresentCapture);
		}

		int target = postSwapTargetWithoutNativeLimiter;
		postSwapTargetWithoutNativeLimiter = 0;
		if (target > 0 && EphemeraCommon.config().framePacerEnabled) {
			boolean captureActive = CaptureOptimizationService.isCaptureActive();
			int spinTailMicros = CapturePacingPolicy.effectiveSpinTailMicros(
				EphemeraCommon.config().framePacerSpinTailMicros,
				captureActive,
				Win32DisplayRuntime.isHighResTimerActive());
			PACER.pacePostSwap(target, spinTailMicros, captureActive);
		}

		int fallback = vanillaExtendedFallbackTarget;
		vanillaExtendedFallbackTarget = 0;
		if (fallback > 0 && !EphemeraCommon.config().framePacerEnabled) {
			FramerateLimiter.limitDisplayFPS(fallback);
		}
	}

	/**
	 * Called at {@code FramerateLimiter.limitDisplayFPS(int)} HEAD.
	 *
	 * @return {@code true} if Ephemera handled the wait and vanilla's limiter should be cancelled
	 */
	public static boolean onPostSwapLimit(int fps) {
		postSwapTargetWithoutNativeLimiter = 0;
		long now = System.nanoTime();
		finishCpuWork(now);
		EphemeraConfig cfg = EphemeraCommon.config();
		boolean managedAurora = ownsAuroraPacing();
		if ((!managedAurora && BUDGET.isExternalPacerActive(now)) || !cfg.framePacerEnabled) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean captureActive = CaptureOptimizationService.isCaptureActive();
		int activeLimit = resolveUserLimit(fps, cfg);
		int active = resolveTargetFps(fps, activeLimit, managedAurora);
		if (captureActive) {
			active = CapturePacingPolicy.resolveCaptureFpsCap(cfg.captureFpsCapMode, safeRefreshRate(mc, now), active);
		}
		if (active <= 0) {
			PACER.reset();
			return false;
		}
		boolean vsync = usesVsync(mc, managedAurora);
		if (vsync && active >= safeRefreshRate(mc, now)) {
			// VBlank already caps this frame; neither Ephemera nor vanilla should sleep on top of it.
			PACER.reset();
			return true;
		}
		int spinTailMicros = CapturePacingPolicy.effectiveSpinTailMicros(
			cfg.framePacerSpinTailMicros, captureActive, Win32DisplayRuntime.isHighResTimerActive());
		// Non-VSync, or VSync with a sub-refresh cap (e.g. 60 fps menu on a 240 Hz display).
		return PACER.pacePostSwap(active, spinTailMicros, captureActive);
	}

	private static void finishCpuWork(long now) {
		if (!externalBeforeWaitActive && !frameWorkEnded) {
			frameWorkEnded = true;
			BUDGET.endFrameWork(now);
			long start = frameWorkStartNs;
			if (start > 0L) {
				long workNs = now - start;
				if (workNs > 0L) {
					PACER.observeCpuWork(workNs, frameHadGameTick);
				}
			}
		}
	}

	private static long nsUntilNextGameTick(Minecraft mc) {
		if (mc == null || mc.level == null) {
			return Long.MAX_VALUE;
		}
		if (!(mc.getDeltaTracker() instanceof DeltaTrackerTimerAccessor timer) || timer.ephemera$paused()) {
			return Long.MAX_VALUE;
		}
		float mspt = timer.ephemera$msPerTick();
		if (mspt <= 0.0f) {
			return Long.MAX_VALUE;
		}
		long elapsedMs = Math.max(0L, Util.getMillis() - timer.ephemera$lastMs());
		double msUntilTick = (1.0 - timer.ephemera$deltaTickResidual()) * mspt - elapsedMs;
		if (msUntilTick <= 0.0) {
			return 0L;
		}
		return Math.round(msUntilTick * 1_000_000.0);
	}

	private static int resolveUserLimit(int nativeFps, EphemeraConfig cfg) {
		return FrameRateLimitPolicy.resolveEffectiveLimit(nativeFps, cfg.extendedFpsLimit);
	}

	private static int resolveTargetFps(int nativeFps, int activeUserLimit, boolean managedAurora) {
		if (!managedAurora) {
			return activeUserLimit;
		}
		int auroraCap = auroraLimits.cap().applyAsInt(nativeFps);
		if (auroraCap > 0 && activeUserLimit > 0) {
			return Math.min(auroraCap, activeUserLimit);
		}
		return auroraCap > 0 ? auroraCap : activeUserLimit;
	}

	private static boolean usesVsync(Minecraft mc, boolean managedAurora) {
		return mc != null && mc.options != null && mc.options.enableVsync().get()
			&& !(managedAurora && auroraLimits.forceVsyncOff().getAsBoolean());
	}

	/**
	 * Cached monitor refresh rate: avoids calling {@code Window.findBestMonitor()} every frame
	 * (which triggers Log4j {@code Monitor.toString()} / {@code VideoMode.toString()} formatting
	 * worth ~390 MB per 45s on the render thread!). Re-queries immediately on window move, resize,
	 * or fullscreen toggle, and at most once per second otherwise.
	 */
	private static int safeRefreshRate(Minecraft mc, long nowNs) {
		if (mc == null) {
			return FALLBACK_REFRESH_RATE;
		}
		var win = mc.getWindow();
		if (win == null) {
			return FALLBACK_REFRESH_RATE;
		}
		int x = win.getX();
		int y = win.getY();
		int w = win.getWidth();
		int h = win.getHeight();
		boolean fs = mc.options != null && mc.options.fullscreen().get();
		if (x == cachedWinX && y == cachedWinY && w == cachedWinW && h == cachedWinH && fs == cachedFullscreen
			&& (nowNs - cachedRefreshAtNs) < REFRESH_CACHE_TTL_NS) {
			return cachedRefreshRate;
		}
		int refresh = RenderPlatform.refreshRate(mc);
		cachedRefreshRate = refresh > 0 ? refresh : FALLBACK_REFRESH_RATE;
		cachedWinX = x;
		cachedWinY = y;
		cachedWinW = w;
		cachedWinH = h;
		cachedFullscreen = fs;
		cachedRefreshAtNs = nowNs;
		return cachedRefreshRate;
	}

	/** Dynamic per-frame terrain upload budget in nanoseconds for {@code ChunkRenderBatcher}. */
	public static long currentUploadBudgetNs() {
		EphemeraConfig cfg = EphemeraCommon.config();
		long normal = BUDGET.computeUploadBudgetNs(
			cfg.maxSectionUploadsPerFrameMs,
			cfg.framePacerAdaptiveUploadBudget,
			System.nanoTime()
		);
		return CapturePacingPolicy.effectiveUploadBudgetNs(normal, CaptureOptimizationService.isCaptureActive());
	}

	/**
	 * Latches ONE shared per-frame chunk-streaming budget used by BOTH section snapshots
	 * ({@code LevelExtractorSectionUpdatesMixin}) and Vulkan mesh uploads ({@code ChunkRenderBatcher}).
	 * Returns {@code true} when adaptive streaming budgeting is active for this frame.
	 */
	public static boolean beginFrameStreamingBudget() {
		EphemeraConfig cfg = EphemeraCommon.config();
		if (!cfg.framePacerAdaptiveUploadBudget) {
			return false;
		}
		long captureMaxNs = CapturePacingPolicy.effectiveUploadBudgetNs(
			Math.max(1, cfg.maxSectionUploadsPerFrameMs) * 1_000_000L,
			CaptureOptimizationService.isCaptureActive()
		);
		BUDGET.resolveStreamingDeadlineNs(
			cfg.maxSectionUploadsPerFrameMs,
			true,
			captureMaxNs,
			System.nanoTime()
		);
		return true;
	}

	/**
	 * Shared per-frame chunk-streaming deadline in {@code System.nanoTime()} ticks (or
	 * {@code Long.MAX_VALUE} when uncapped / adaptive budgeting is off).
	 */
	public static long currentStreamingDeadlineNs() {
		EphemeraConfig cfg = EphemeraCommon.config();
		if (!cfg.framePacerAdaptiveUploadBudget) {
			return Long.MAX_VALUE;
		}
		long captureMaxNs = CapturePacingPolicy.effectiveUploadBudgetNs(
			Math.max(1, cfg.maxSectionUploadsPerFrameMs) * 1_000_000L,
			CaptureOptimizationService.isCaptureActive()
		);
		return BUDGET.resolveStreamingDeadlineNs(
			cfg.maxSectionUploadsPerFrameMs,
			true,
			captureMaxNs,
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
