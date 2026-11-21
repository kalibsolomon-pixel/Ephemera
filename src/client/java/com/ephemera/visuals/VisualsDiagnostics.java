package com.ephemera.visuals;

import com.ephemera.api.EphemeraApi;
import com.ephemera.api.EphemeraVisibilityProvider;
import com.ephemera.core.diagnostics.FrameMetrics;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Live frame statistics for the settings screen and the diagnostics HUD. Frames are only sampled
 * while a consumer is watching (HUD enabled or the Diagnostics tab open), so the idle cost is one
 * volatile read per frame.
 */
public final class VisualsDiagnostics {
	private static final FrameMetrics METRICS = new FrameMetrics(600);
	private static final long SNAPSHOT_INTERVAL_NS = 250_000_000L;

	private static volatile int viewers;
	private static FrameMetrics.Snapshot cached = FrameMetrics.Snapshot.EMPTY;
	private static long cachedAtNs;
	private static String backend;

	private VisualsDiagnostics() {}

	/** A screen or overlay calls this while it needs live data; pair with {@link #release()}. */
	public static void acquire() {
		if (viewers++ == 0) {
			METRICS.reset();
		}
	}

	public static void release() {
		if (viewers > 0) {
			viewers--;
		}
	}

	public static boolean wantsFrames() {
		return viewers > 0 || VisualsRuntime.cfg().diagnosticsHudOverlay;
	}

	/** Called once per rendered frame. */
	public static void onFrame() {
		if (wantsFrames()) {
			METRICS.recordTimestamp(System.nanoTime());
		}
	}

	public static FrameMetrics.Snapshot snapshot() {
		long now = System.nanoTime();
		if (now - cachedAtNs >= SNAPSHOT_INTERVAL_NS) {
			cached = METRICS.snapshot();
			cachedAtNs = now;
		}
		return cached;
	}

	public static String backendName() {
		if (backend == null) {
			GpuDevice device = RenderSystem.tryGetDevice();
			if (device != null) {
				backend = device.getDeviceInfo().backendName();
			} else {
				return EphemeraApi.get().map(s -> s.capabilities().graphicsBackend()).orElse("UNKNOWN");
			}
		}
		if (VulkanModernCapabilities.isMeshEngineActive() && "Vulkan".equals(backend)) {
			return VulkanModernCapabilities.get().taskShaderEnabled() ? "Vulkan • Task+Mesh" : "Vulkan • Mesh";
		}
		return backend;
	}

	public static EphemeraVisibilityProvider visibility() {
		return EphemeraApi.get().map(EphemeraApi.Service::visibility).orElse(null);
	}
}
