package com.ephemera.render.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional zero-overhead per-phase Vulkan frame timing tracker enabled only when
 * {@code -Dephemera.diag.vulkan=true} is passed. Measures wall-clock microseconds spent in
 * {@code acquireNextTexture}, {@code blitFromTexture}, {@code submit} (including
 * {@code vkWaitSemaphores}), and {@code present}.
 */
public final class VulkanDiagTracker {
	public static final boolean ENABLED = Boolean.getBoolean("ephemera.diag.vulkan");
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/VulkanDiag");

	private static long frames;
	private static long acquireNs;
	private static long blitNs;
	private static long submitNs;
	private static long waitSemNs;
	private static long presentNs;
	private static volatile long workerSubNs;
	private static volatile long workerPresNs;
	private static volatile long workerAcqNs;
	private static volatile long workerJobs;
	private static long lastReportNs;
	private static String lastPresentMode = "?";

	private VulkanDiagTracker() {}

	public static void notePresentMode(String mode, int images) {
		notePresentMode(mode, images, null);
	}

	public static void notePresentMode(String mode, int images, String extra) {
		lastPresentMode = mode + "(" + images + " imgs)";
		if (ENABLED) {
			if (extra != null && !extra.isEmpty()) {
				LOGGER.info("Swapchain configured: {} [{}]", lastPresentMode, extra);
			} else {
				LOGGER.info("Swapchain configured: {}", lastPresentMode);
			}
		}
	}

	public static void recordWorkerSubmit(long ns) {
		workerSubNs += ns;
	}

	public static void recordWorkerPresentAcquire(long presNs, long acqNs) {
		workerPresNs += presNs;
		workerAcqNs += acqNs;
		workerJobs++;
	}

	public static void recordAcquire(long ns) {
		acquireNs += ns;
	}

	public static void recordBlit(long ns) {
		blitNs += ns;
	}

	public static void recordSubmit(long ns) {
		submitNs += ns;
	}

	public static void recordWaitSem(long ns) {
		waitSemNs += ns;
	}

	public static void recordPresent(long ns) {
		presentNs += ns;
		frames++;
		long now = System.nanoTime();
		if (lastReportNs == 0L) {
			lastReportNs = now;
			return;
		}
		long elapsed = now - lastReportNs;
		if (elapsed >= 2_000_000_000L && frames > 0L) {
			double inv = 1.0 / (frames * 1000.0); // ns -> avg us/frame
			double totalFrameUs = (elapsed / 1000.0) / frames;
			double acqUs = acquireNs * inv;
			double bltUs = blitNs * inv;
			double subUs = submitNs * inv;
			double waitUs = waitSemNs * inv;
			double qsubUs = Math.max(0.0, subUs - waitUs);
			double presUs = presentNs * inv;
			double cpuRenderUs = Math.max(0.0, totalFrameUs - acqUs - bltUs - subUs - presUs);
			long wj = Math.max(1L, workerJobs);
			double wSubUs = workerSubNs / (wj * 1000.0);
			double wPresUs = workerPresNs / (wj * 1000.0);
			double wAcqUs = workerAcqNs / (wj * 1000.0);
			LOGGER.info(
				"VkDiag [{}]: fps={} frame={}us | render={}us acq={}us blit={}us qsub={}us waitSem={}us pres={}us | worker[sub={}us pres={}us acq={}us]",
				lastPresentMode,
				String.format("%.1f", frames * 1e9 / elapsed),
				String.format("%.0f", totalFrameUs),
				String.format("%.0f", cpuRenderUs),
				String.format("%.0f", acqUs),
				String.format("%.0f", bltUs),
				String.format("%.0f", qsubUs),
				String.format("%.0f", waitUs),
				String.format("%.0f", presUs),
				String.format("%.0f", wSubUs),
				String.format("%.0f", wPresUs),
				String.format("%.0f", wAcqUs)
			);
			frames = 0L;
			acquireNs = 0L;
			blitNs = 0L;
			submitNs = 0L;
			waitSemNs = 0L;
			presentNs = 0L;
			workerSubNs = 0L;
			workerPresNs = 0L;
			workerAcqNs = 0L;
			workerJobs = 0L;
			lastReportNs = now;
		}
	}
}
