package com.ephemera.render.pacing;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Detects running streaming / screen-recording applications and coordinates Ephemera's
 * capture-friendly scheduling, swapchain headroom, spin-wait reduction, upload smoothing, and
 * optional FPS pacing.
 *
 * <p>In {@link EphemeraConfig.CaptureOptimizationMode#AUTO}, a low-priority daemon thread polls
 * running capture executables every ~3.5 seconds as a running-app heuristic. Detection evidence is
 * stamped with a mode generation so an in-flight scan can never overwrite a newer manual
 * {@code OFF} or {@code ON} change.
 */
public final class CaptureOptimizationService {
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/CaptureOpt");
	private static final long POLL_INTERVAL_MS = 3500L;

	record CaptureState(boolean active, String source) {
		static final CaptureState INACTIVE = new CaptureState(false, null);
	}

	private static final Object STATE_LOCK = new Object();
	private static long modeGeneration;
	private static volatile CaptureState state = CaptureState.INACTIVE;
	private static volatile boolean initialized;
	private static volatile Thread probeThread;
	private static volatile Supplier<String> processDetector = CaptureOptimizationService::detectCaptureProcess;
	private static volatile Supplier<EphemeraConfig> configSupplier = EphemeraCommon::config;

	private CaptureOptimizationService() {}

	public static void init() {
		if (initialized) {
			refreshNow();
			return;
		}
		initialized = true;
		refreshModeImmediate();
		Thread t = new Thread(CaptureOptimizationService::pollLoop, "Ephemera-CaptureProbe");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
		probeThread = t;
	}

	/** Zero-overhead render-thread check: manual OFF/ON always take immediate precedence. */
	public static boolean isCaptureActive() {
		EphemeraConfig.CaptureOptimizationMode mode = currentMode();
		if (mode == EphemeraConfig.CaptureOptimizationMode.OFF) {
			return false;
		}
		if (mode == EphemeraConfig.CaptureOptimizationMode.ON) {
			return true;
		}
		return state.active();
	}

	/** Re-evaluates mode immediately (e.g. after toggling Stream / Recording Mode in Settings). */
	public static void refreshNow() {
		refreshModeImmediate();
		Thread t = probeThread;
		if (t != null) {
			t.interrupt();
		}
	}

	private static void refreshModeImmediate() {
		synchronized (STATE_LOCK) {
			modeGeneration++;
			EphemeraConfig.CaptureOptimizationMode mode = currentMode();
			if (mode == EphemeraConfig.CaptureOptimizationMode.OFF) {
				updateStateLocked(false, null);
			} else if (mode == EphemeraConfig.CaptureOptimizationMode.ON) {
				updateStateLocked(true, "Manual");
			} else if ("Manual".equals(state.source())) {
				// Switching ON -> AUTO clears the manual override until a fresh probe reports an app.
				updateStateLocked(false, null);
			}
		}
	}

	private static void pollLoop() {
		while (true) {
			try {
				pollOnce();
			} catch (Throwable t) {
				LOGGER.debug("Capture probe pass failed: {}", t.toString());
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException ignored) {
				// Woken by refreshNow()
			}
		}
	}

	/** Package-private for deterministic concurrency tests. */
	static void pollOnce() {
		long expectedGeneration;
		synchronized (STATE_LOCK) {
			expectedGeneration = modeGeneration;
			EphemeraConfig.CaptureOptimizationMode mode = currentMode();
			if (mode == EphemeraConfig.CaptureOptimizationMode.OFF) {
				updateStateLocked(false, null);
				return;
			}
			if (mode == EphemeraConfig.CaptureOptimizationMode.ON) {
				updateStateLocked(true, "Manual");
				return;
			}
		}
		String detected = processDetector.get();
		synchronized (STATE_LOCK) {
			EphemeraConfig.CaptureOptimizationMode mode = currentMode();
			if (mode == EphemeraConfig.CaptureOptimizationMode.OFF) {
				updateStateLocked(false, null);
				return;
			}
			if (mode == EphemeraConfig.CaptureOptimizationMode.ON) {
				updateStateLocked(true, "Manual");
				return;
			}
			if (modeGeneration != expectedGeneration) {
				// Mode changed while scanning; discard stale probe result.
				return;
			}
			updateStateLocked(detected != null, detected);
		}
	}

	/** Test seam: replaces the OS process detector (null restores the default). */
	static void setProcessDetectorForTest(Supplier<String> detector) {
		processDetector = detector != null ? detector : CaptureOptimizationService::detectCaptureProcess;
	}

	/** Test seam: replaces the config supplier (null restores the default). */
	static void setConfigSupplierForTest(Supplier<EphemeraConfig> supplier) {
		configSupplier = supplier != null ? supplier : EphemeraCommon::config;
	}

	static CaptureState currentStateForTest() {
		return state;
	}

	private static EphemeraConfig.CaptureOptimizationMode currentMode() {
		EphemeraConfig.CaptureOptimizationMode mode = configSupplier.get().captureOptimizationMode;
		return mode != null ? mode : EphemeraConfig.CaptureOptimizationMode.AUTO;
	}

	/**
	 * Capture hook DLLs are no signal: OBS registers an implicit Vulkan layer that loads its hook
	 * into every Vulkan game, whether or not OBS is running. A running capture app is.
	 */
	private static String detectCaptureProcess() {
		try {
			Optional<String> found = ProcessHandle.allProcesses()
				.map(ph -> {
					try {
						return ph.info().command().orElse(null);
					} catch (Throwable ignored) {
						return null;
					}
				})
				.filter(cmd -> cmd != null && !cmd.isEmpty())
				.map(CaptureOptimizationService::classifyCaptureProcess)
				.filter(label -> label != null)
				.findFirst();
			return found.orElse(null);
		} catch (Throwable ignored) {
			return null;
		}
	}

	static String classifyCaptureProcess(String commandPath) {
		String fileName;
		try {
			Path p = Path.of(commandPath).getFileName();
			fileName = (p != null ? p.toString() : commandPath).toLowerCase(Locale.ROOT);
		} catch (Throwable t) {
			int slash = Math.max(commandPath.lastIndexOf('\\'), commandPath.lastIndexOf('/'));
			fileName = (slash >= 0 ? commandPath.substring(slash + 1) : commandPath).toLowerCase(Locale.ROOT);
		}
		return switch (fileName) {
			case "obs64.exe", "obs32.exe", "obs" -> "OBS";
			case "streamlabs obs.exe", "streamlabs desktop.exe" -> "Streamlabs";
			case "xsplit.broadcaster.exe" -> "XSplit";
			case "medalencoder.exe" -> "Medal";
			case "bdcam.exe" -> "Bandicam";
			case "action.exe", "action_x64.bin" -> "Action!";
			default -> null;
		};
	}

	private static void updateStateLocked(boolean active, String source) {
		CaptureState prev = state;
		CaptureState next = new CaptureState(active, source);
		state = next;
		if (prev.active() != next.active() || (next.active() && !Objects.equals(prev.source(), next.source()))) {
			if (next.active()) {
				LOGGER.info("Streaming/recording optimization active ({})", next.source());
			} else if (prev.active()) {
				LOGGER.info("Streaming/recording optimization inactive");
			}
		}
	}

	public static String statusLabel() {
		EphemeraConfig.CaptureOptimizationMode mode = currentMode();
		if (mode == EphemeraConfig.CaptureOptimizationMode.OFF) {
			return "Off";
		}
		if (mode == EphemeraConfig.CaptureOptimizationMode.ON) {
			return "Active (Manual)";
		}
		CaptureState snap = state;
		if (snap.active()) {
			String src = snap.source();
			return src != null ? "Active (Auto: " + src + ")" : "Active (Auto)";
		}
		return "Standby (Auto)";
	}
}
