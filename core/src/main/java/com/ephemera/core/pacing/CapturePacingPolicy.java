package com.ephemera.core.pacing;

import com.ephemera.core.config.EphemeraConfig;

/**
 * Pure-Java presentation and capture-headroom pacing policies shared across Minecraft versions.
 *
 * <p>When screen recording or streaming (OBS, Discord, Medal, WGC) is active, uncapped rendering
 * saturates the GPU 3D queue, hammers capture hooks at 1000+ presents/sec, and starves the video
 * encoder and compositor of both GPU slices and CPU cores. These helpers compute:
 * <ul>
 *   <li>A clean capture-aligned FPS cap (respecting any stricter user cap).</li>
 *   <li>Tighter spin-wait tails so idle frame time sleeps and yields CPU cores to the encoder.</li>
 *   <li>Smoothed per-frame chunk upload budgets so mesh uploads never spike capture latency.</li>
 *   <li>Swapchain image headroom for Vulkan borderless / capture presentation.</li>
 * </ul>
 */
public final class CapturePacingPolicy {
	private static final int FALLBACK_REFRESH_HZ = 60;
	private static final long MIN_UPLOAD_BUDGET_NS = 500_000L;
	private static final long CAPTURE_UPLOAD_CEILING_NS = 1_500_000L;

	private CapturePacingPolicy() {}

	/**
	 * Resolves the effective FPS cap while capture optimization is active.
	 *
	 * @param mode               configured capture FPS cap mode
	 * @param monitorRefreshRate current monitor refresh rate in Hz (&lt;= 0 falls back to 60)
	 * @param existingCap        existing user/Aurora FPS cap (&lt;= 0 means uncapped)
	 * @return effective FPS cap (&lt;= 0 means uncapped)
	 */
	public static int resolveCaptureFpsCap(
		EphemeraConfig.CaptureFpsCapMode mode,
		int monitorRefreshRate,
		int existingCap
	) {
		int base = Math.max(0, existingCap);
		if (mode == null || mode == EphemeraConfig.CaptureFpsCapMode.OFF) {
			return base;
		}
		int captureCap = switch (mode) {
			case OFF -> 0;
			case REFRESH_RATE -> monitorRefreshRate > 0 ? monitorRefreshRate : FALLBACK_REFRESH_HZ;
			case FPS_240 -> 240;
			case FPS_180 -> 180;
			case FPS_120 -> 120;
			case FPS_60 -> 60;
		};
		if (captureCap <= 0) {
			return base;
		}
		return base > 0 ? Math.min(base, captureCap) : captureCap;
	}

	/**
	 * Computes the spin-tail duration for {@link EphemeraFramePacer}. While capturing, we cap the
	 * busy-spin tail so the render thread parks cleanly and hands CPU time to OBS/Discord/encoders.
	 */
	public static int effectiveSpinTailMicros(
		int configuredSpinTailMicros,
		boolean captureActive,
		boolean highResTimerActive
	) {
		int clamped = Math.max(100, Math.min(3000, configuredSpinTailMicros));
		if (!captureActive) {
			return clamped;
		}
		int captureCap = highResTimerActive ? 300 : 450;
		return Math.min(clamped, captureCap);
	}

	/**
	 * Tightens per-frame terrain upload budgets while capturing so sudden chunk-streaming bursts
	 * do not saturate the PCIe/VRAM transfer queue while the capture pipeline reads frames.
	 */
	public static long effectiveUploadBudgetNs(long normalBudgetNs, boolean captureActive) {
		long base = Math.max(MIN_UPLOAD_BUDGET_NS, normalBudgetNs);
		if (!captureActive) {
			return base;
		}
		long scaled = (base * 65L) / 100L;
		return Math.max(MIN_UPLOAD_BUDGET_NS, Math.min(CAPTURE_UPLOAD_CEILING_NS, scaled));
	}

	/**
	 * Computes the preferred Vulkan swapchain {@code minImageCount}.
	 *
	 * <p>In uncapped modes ({@code IMMEDIATE} / {@code MAILBOX}), Ephemera's async presenter
	 * pre-acquires the next image immediately behind {@code vkQueuePresentKHR} while 2 GPU frames
	 * are in flight, and the compositor/DWM (windowed, borderless, or FSE-disallowed exclusive)
	 * or a capture hook holds the just-presented image; requesting 4 images prevents
	 * {@code vkAcquireNextImageKHR} stalls without adding queue latency. FIFO/VSync modes keep
	 * vanilla's count so presentation latency is unchanged.
	 */
	public static int preferredVulkanSwapchainImages(
		int surfaceMinImages,
		int surfaceMaxImages,
		int vanillaRequestedImages,
		boolean uncappedPresentMode,
		boolean borderlessFullscreen,
		boolean captureActive
	) {
		int minImg = Math.max(1, surfaceMinImages);
		int desired = Math.max(vanillaRequestedImages, minImg);
		if (uncappedPresentMode) {
			desired = Math.max(desired, 4);
		}
		if (surfaceMaxImages > 0) {
			desired = Math.min(desired, surfaceMaxImages);
		}
		return Math.max(minImg, desired);
	}
}
