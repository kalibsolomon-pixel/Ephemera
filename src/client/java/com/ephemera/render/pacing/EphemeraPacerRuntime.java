package com.ephemera.render.pacing;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.core.pacing.EphemeraFramePacer;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.platform.RenderPlatform;
import net.minecraft.client.Minecraft;

import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Render-thread runtime owner of Ephemera's precision frame pacer, adaptive terrain-upload budget,
 * and cooperative handshake with Aurora Client's frame pacer.
 *
 * <p>A paired Aurora Client supplies its cap and effective VSync preference over ObjectShare.
 * Ephemera owns the wait and its upload budget. Older clients still report waits already taken.
 */
public final class EphemeraPacerRuntime {
	private static final int VANILLA_UNLIMITED_FPS = 260;
	private static final int FALLBACK_REFRESH_RATE = 60;

	private static final EphemeraFramePacer PACER = new EphemeraFramePacer();
	private static final FrameBudgetPacer BUDGET = new FrameBudgetPacer();

	private static volatile boolean externalBeforeWaitActive;
	private static volatile AuroraLimits auroraLimits;
	private static int postSwapTargetWithoutNativeLimiter;

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

	/**
	 * Called at {@code Minecraft.runTick} HEAD before input and render work.
	 */
	public static void onFrameHead(Minecraft mc) {
		EphemeraConfig cfg = EphemeraCommon.config();
		long now = System.nanoTime();
		int nativeLimit = mc.getFramerateLimitTracker().getFramerateLimit();
		int activeNative = activeNativeLimit(nativeLimit);
		boolean managedAurora = ownsAuroraPacing();
		postSwapTargetWithoutNativeLimiter = 0;

		if (!managedAurora && BUDGET.isExternalPacerActive(now)) {
			// Aurora owns the blocking wait; Ephemera only updates frame work & budget state.
			int effective = BUDGET.effectiveTargetFps(now, activeNative);
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.AURORA, effective);
			BUDGET.beginFrameWork(now);
			return;
		}

		int target = managedAurora ? auroraLimits.cap().applyAsInt(nativeLimit) : activeNative;
		boolean vsync = usesVsync(mc, managedAurora);
		if (vsync) {
			// Never sleep before render under VSync: the present already blocks on VBlank, so a
			// frame-head wait phase-locks into missed VBlanks and halves/thirds the framerate.
			// Only drop frame-head ownership; a sub-refresh post-swap cadence must survive across frames.
			PACER.paceFrameHead(0, false, cfg.framePacerSpinTailMicros);
			int refresh = safeRefreshRate(mc);
			int effectiveBudgetFps = target > 0 ? Math.min(target, refresh) : refresh;
			if (managedAurora && activeNative == 0 && target > 0 && target < refresh) {
				postSwapTargetWithoutNativeLimiter = target;
			}
			BUDGET.setActiveOwner(cfg.framePacerEnabled
				? FrameBudgetPacer.PacerOwner.EPHEMERA
				: FrameBudgetPacer.PacerOwner.VANILLA, effectiveBudgetFps);
			BUDGET.beginFrameWork(now);
			return;
		}

		if (!cfg.framePacerEnabled) {
			PACER.reset();
			BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.VANILLA, activeNative);
			BUDGET.beginFrameWork(now);
			return;
		}

		BUDGET.setActiveOwner(FrameBudgetPacer.PacerOwner.EPHEMERA, target);
		if (target > 0 && cfg.framePacerLowLatency) {
			// Pace right before input/tick/render for lowest input-to-photon latency.
			PACER.paceFrameHead(target, true, cfg.framePacerSpinTailMicros);
			now = System.nanoTime();
		} else {
			// Ensure frame-head state is cleared if low-latency mode or FPS cap turned off.
			PACER.paceFrameHead(0, false, cfg.framePacerSpinTailMicros);
			if (managedAurora && activeNative == 0) {
				postSwapTargetWithoutNativeLimiter = target;
			}
		}
		BUDGET.beginFrameWork(now);
	}

	/** Called at {@code Minecraft.renderFrame} RETURN to measure pure CPU frame work. */
	public static void onRenderFrameEnd() {
		if (!externalBeforeWaitActive) {
			BUDGET.endFrameWork(System.nanoTime());
		}
		externalBeforeWaitActive = false;
		int target = postSwapTargetWithoutNativeLimiter;
		postSwapTargetWithoutNativeLimiter = 0;
		if (target > 0) {
			PACER.pacePostSwap(target, EphemeraCommon.config().framePacerSpinTailMicros);
		}
	}

	/**
	 * Called at {@code FramerateLimiter.limitDisplayFPS(int)} HEAD.
	 *
	 * @return {@code true} if Ephemera handled the wait and vanilla's limiter should be cancelled
	 */
	public static boolean onPostSwapLimit(int fps) {
		postSwapTargetWithoutNativeLimiter = 0;
		EphemeraConfig cfg = EphemeraCommon.config();
		long now = System.nanoTime();
		boolean managedAurora = ownsAuroraPacing();
		if ((!managedAurora && BUDGET.isExternalPacerActive(now)) || !cfg.framePacerEnabled) {
			return false;
		}
		int active = managedAurora ? auroraLimits.cap().applyAsInt(fps) : activeNativeLimit(fps);
		if (active <= 0) {
			PACER.reset();
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean vsync = usesVsync(mc, managedAurora);
		if (vsync && active >= safeRefreshRate(mc)) {
			// VBlank already caps this frame; neither Ephemera nor vanilla should sleep on top of it.
			PACER.reset();
			return true;
		}
		// Non-VSync, or VSync with a sub-refresh cap (e.g. 60 fps menu on a 240 Hz display).
		return PACER.pacePostSwap(active, cfg.framePacerSpinTailMicros);
	}

	private static int activeNativeLimit(int nativeFps) {
		return nativeFps > 0 && nativeFps < VANILLA_UNLIMITED_FPS ? nativeFps : 0;
	}

	private static boolean usesVsync(Minecraft mc, boolean managedAurora) {
		return mc != null && mc.options != null && mc.options.enableVsync().get()
			&& !(managedAurora && auroraLimits.forceVsyncOff().getAsBoolean());
	}

	private static int safeRefreshRate(Minecraft mc) {
		int refresh = RenderPlatform.refreshRate(mc);
		return refresh > 0 ? refresh : FALLBACK_REFRESH_RATE;
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
