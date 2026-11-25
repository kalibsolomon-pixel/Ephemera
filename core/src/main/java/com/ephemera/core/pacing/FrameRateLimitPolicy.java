package com.ephemera.core.pacing;

/**
 * Pure-Java definition and mapping for Ephemera's extended frame rate limit slider.
 *
 * <p>Scale:
 * <ul>
 *   <li>{@code 10..250} in steps of {@code 10} (vanilla range, synced to {@code options.txt})</li>
 *   <li>{@code 260..300} in steps of {@code 10} (extended past 250 to 300)</li>
 *   <li>{@code 400..1000} in steps of {@code 100} (high-refresh caps up to 1000 fps)</li>
 *   <li>{@code 0} = Unlimited (final stop)</li>
 * </ul>
 *
 * <p>Vanilla {@code options.txt} stores {@code maxFps} in {@code [10, 260]} where {@code 260} is
 * Unlimited. To preserve complete compatibility with vanilla saves and existing users who had
 * {@code maxFps:260} (Unlimited), extended caps ({@code 260..1000}) live in
 * {@code EphemeraConfig.extendedFpsLimit} while keeping vanilla's option at {@code 260}, and only
 * apply when vanilla's tracker is unthrottled ({@code >= 260}). Any stricter vanilla/AFK/minimized
 * throttle ({@code < 260}) automatically wins.
 */
public final class FrameRateLimitPolicy {
	public static final int UNLIMITED = 0;
	public static final int VANILLA_UNLIMITED_CUTOFF = 260;
	public static final int MIN_FPS = 10;
	public static final int MAX_FINITE_FPS = 1000;
	public static final int DEFAULT_FPS = 120;

	private static final int[] STOPS = buildStops();

	private static int[] buildStops() {
		// 10..300 step 10 (30 stops) + 400..1000 step 100 (7 stops) + Unlimited (0) = 38 stops
		int[] s = new int[38];
		int idx = 0;
		for (int fps = 10; fps <= 300; fps += 10) {
			s[idx++] = fps;
		}
		for (int fps = 400; fps <= 1000; fps += 100) {
			s[idx++] = fps;
		}
		s[idx] = UNLIMITED;
		return s;
	}

	private FrameRateLimitPolicy() {}

	public static int stopCount() {
		return STOPS.length;
	}

	public static int stopAt(int index) {
		int clamped = Math.max(0, Math.min(STOPS.length - 1, index));
		return STOPS[clamped];
	}

	public static boolean isExtendedStop(int fps) {
		return fps >= VANILLA_UNLIMITED_CUTOFF && fps <= MAX_FINITE_FPS && isValidStop(fps);
	}

	public static boolean isValidStop(int fps) {
		if (fps == UNLIMITED) {
			return true;
		}
		if (fps >= 10 && fps <= 300) {
			return fps % 10 == 0;
		}
		if (fps >= 400 && fps <= 1000) {
			return fps % 100 == 0;
		}
		return false;
	}

	/** Sanitizes {@code EphemeraConfig.extendedFpsLimit} (0 = none, or valid extended stop 260..1000). */
	public static int sanitizeExtended(int raw) {
		if (raw < VANILLA_UNLIMITED_CUTOFF) {
			return UNLIMITED;
		}
		if (raw <= 300) {
			return Math.max(260, Math.min(300, ((raw + 5) / 10) * 10));
		}
		if (raw < 350) {
			return 300;
		}
		if (raw >= MAX_FINITE_FPS) {
			return MAX_FINITE_FPS;
		}
		return Math.max(400, Math.min(MAX_FINITE_FPS, ((raw + 50) / 100) * 100));
	}

	/**
	 * Resolves the effective FPS cap ({@code 0} = unlimited, {@code 1..1000} = active cap) from
	 * vanilla's {@code FramerateLimitTracker.getFramerateLimit()} and Ephemera's
	 * {@code extendedFpsLimit}.
	 */
	public static int resolveEffectiveLimit(int vanillaTrackerLimit, int extendedConfigLimit) {
		int ext = sanitizeExtended(extendedConfigLimit);
		if (vanillaTrackerLimit > 0 && vanillaTrackerLimit < VANILLA_UNLIMITED_CUTOFF) {
			// Either a vanilla 10..250 cap or a runtime throttle (menu=60, AFK=30/10, minimized=10, unfocused).
			return ext > 0 ? Math.min(vanillaTrackerLimit, ext) : vanillaTrackerLimit;
		}
		return ext;
	}

	/** Maps any finite FPS or {@link #UNLIMITED} (0) to the nearest ordered stop index. */
	public static int nearestIndex(int fps) {
		if (fps <= 0) {
			return STOPS.length - 1;
		}
		if (fps <= 10) {
			return 0;
		}
		if (fps <= 300) {
			return Math.min(29, (fps - 10 + 5) / 10);
		}
		if (fps < 350) {
			return 29; // 300
		}
		if (fps >= 1000) {
			return 36; // 1000
		}
		return 30 + Math.min(6, (fps - 400 + 50) / 100);
	}

	public static double toSliderFraction(int fps) {
		return (double) nearestIndex(fps) / (STOPS.length - 1);
	}

	public static int fromSliderFraction(double fraction) {
		double clamped = Math.max(0.0, Math.min(1.0, Double.isFinite(fraction) ? fraction : 1.0));
		int index = (int) Math.round(clamped * (STOPS.length - 1));
		return stopAt(index);
	}

	public static int step(int currentFps, int dir) {
		int idx = nearestIndex(currentFps);
		return stopAt(idx + Integer.signum(dir));
	}
}
