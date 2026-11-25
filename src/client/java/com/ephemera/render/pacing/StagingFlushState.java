package com.ephemera.render.pacing;

/**
 * Flags the render-thread emergency upload path: when a worker-side section append fails because
 * the staging arena is full and the render thread invokes the upload inside
 * {@code addSectionBuffersToUberBuffer}, pacing is bypassed for that call so the arena drains
 * fully and the retrying task makes progress instead of spinning.
 *
 * <p>Render thread only.
 */
public final class StagingFlushState {
	private static volatile boolean emergencyFlush;

	private StagingFlushState() {}

	public static boolean isEmergencyFlush() {
		return emergencyFlush;
	}

	public static void set(boolean value) {
		emergencyFlush = value;
	}
}
