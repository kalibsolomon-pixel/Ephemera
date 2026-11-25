package com.ephemera.platform;

import com.ephemera.EphemeraCommon;
import net.minecraft.util.Util;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WindowsLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Windows scheduling and presentation tweaks for borderless fullscreen and capture, through
 * LWJGL's Win32 bindings. Every call fails soft on older Windows builds and is a no-op elsewhere.
 * <ul>
 *   <li>1 ms timer resolution ({@code timeBeginPeriod}) and high-resolution waitable timer, so
 *       frame pacer parks wake on time.</li>
 *   <li>Power Throttling / EcoQoS opt-out (restored to OS defaults when disabled), so Windows 11
 *       does not slow the game or ignore its timer resolution while another window (OBS, Discord)
 *       has focus.</li>
 *   <li>DWM corner and border attributes that keep borderless fullscreen eligible for
 *       Independent Flip.</li>
 * </ul>
 */
public final class Win32DisplayRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/Win32Display");
	public static final boolean IS_WINDOWS = Util.getPlatform() == Util.OS.WINDOWS;

	// DWM window attributes (Windows 11 22000+, safely ignored with HRESULT error on Windows 10)
	private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
	private static final int DWMWA_BORDER_COLOR = 34;
	private static final int DWMWCP_DEFAULT = 0;
	private static final int DWMWCP_DONOTROUND = 1;
	private static final int DWMWA_COLOR_NONE = 0xFFFFFFFE;
	private static final int DWMWA_COLOR_DEFAULT = 0xFFFFFFFF;

	// Win32 ProcessPowerThrottling & ThreadPowerThrottling (Windows 10 1709+ / Windows 11 22H2+)
	private static final int PROCESS_POWER_THROTTLING = 4;
	private static final int PROCESS_POWER_THROTTLING_CURRENT_VERSION = 1;
	private static final int PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1;
	private static final int PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4;
	private static final int THREAD_POWER_THROTTLING = 3;
	private static final int THREAD_POWER_THROTTLING_CURRENT_VERSION = 1;
	private static final int THREAD_POWER_THROTTLING_EXECUTION_SPEED = 0x1;
	private static final int THREAD_PRIORITY_NORMAL = 0;
	private static final int THREAD_PRIORITY_ABOVE_NORMAL = 1;

	// Win32 High-Resolution Waitable Timer (Windows 10 1803+ / Windows 11)
	private static final int CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 0x00000002;
	private static final int TIMER_WAIT_ACCESS = 0x00100002; // SYNCHRONIZE | TIMER_MODIFY_STATE
	private static final long WAIT_OBJECT_0 = 0L;

	private static final long BORDERLESS_STRIP_STYLE =
		(long) (User32.WS_CAPTION | User32.WS_THICKFRAME | User32.WS_BORDER | User32.WS_DLGFRAME
			| User32.WS_MINIMIZEBOX | User32.WS_MAXIMIZEBOX | User32.WS_SYSMENU);
	private static final long BORDERLESS_STRIP_EXSTYLE =
		(long) (User32.WS_EX_DLGMODALFRAME | User32.WS_EX_WINDOWEDGE
			| User32.WS_EX_CLIENTEDGE | User32.WS_EX_STATICEDGE);

	/** Injectable native waitable-timer operations for deterministic lifecycle testing. */
	interface NativeTimerOps {
		long createHandle();
		LongBuffer allocDueTimeBuffer();
		long bufferAddress(LongBuffer buf);
		boolean waitNanos(long handle, long dueTimeAddr, long nanos);
		void closeHandle(long handle);
		void freeDueTimeBuffer(LongBuffer buf);
	}

	/**
	 * Coordinates render-thread waitable-timer usage against JVM shutdown cleanup so the shutdown
	 * hook can never free the native due-time buffer or close the handle while a wait is in flight.
	 */
	static final class WaitableTimerGuard {
		private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		private final NativeTimerOps ops;
		private volatile boolean active;
		private volatile boolean shuttingDown;
		private volatile Thread owner;
		private long handle;
		private LongBuffer dueTimeBuf;
		private long dueTimeAddr;

		WaitableTimerGuard(NativeTimerOps ops) {
			this.ops = ops;
		}

		boolean init(Thread ownerThread) {
			if (shuttingDown || active) {
				return active;
			}
			lock.writeLock().lock();
			try {
				if (shuttingDown || active) {
					return active;
				}
				long h = ops.createHandle();
				if (h == 0L) {
					return false;
				}
				LongBuffer buf = ops.allocDueTimeBuffer();
				if (buf == null) {
					ops.closeHandle(h);
					return false;
				}
				long addr = ops.bufferAddress(buf);
				if (addr == 0L) {
					ops.freeDueTimeBuffer(buf);
					ops.closeHandle(h);
					return false;
				}
				this.handle = h;
				this.dueTimeBuf = buf;
				this.dueTimeAddr = addr;
				this.owner = ownerThread;
				this.active = true;
				return true;
			} catch (Throwable t) {
				LOGGER.debug("CREATE_WAITABLE_TIMER_HIGH_RESOLUTION unavailable: {}", t.toString());
				return false;
			} finally {
				lock.writeLock().unlock();
			}
		}

		boolean tryParkNanos(long nanos, Thread caller) {
			if (shuttingDown || !active || caller != owner) {
				return false;
			}
			// Never block waiting for shutdown to finish: fall back immediately once shutdown holds the write lock.
			if (!lock.readLock().tryLock()) {
				return false;
			}
			try {
				if (shuttingDown || !active || handle == 0L || dueTimeAddr == 0L) {
					return false;
				}
				return ops.waitNanos(handle, dueTimeAddr, nanos);
			} finally {
				lock.readLock().unlock();
			}
		}

		void shutdown(long maxWaitMs) {
			shuttingDown = true;
			active = false;
			boolean acquired = false;
			try {
				acquired = lock.writeLock().tryLock(maxWaitMs, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!acquired) {
				// An in-flight native wait did not finish in time; never free native memory while still in use.
				LOGGER.debug("Skipped freeing Win32 waitable timer because a native wait was still in progress at shutdown");
				return;
			}
			try {
				long h = handle;
				handle = 0L;
				LongBuffer buf = dueTimeBuf;
				dueTimeBuf = null;
				dueTimeAddr = 0L;
				owner = null;
				if (h != 0L) {
					try {
						ops.closeHandle(h);
					} catch (Throwable ignored) {
					}
				}
				if (buf != null) {
					try {
						ops.freeDueTimeBuffer(buf);
					} catch (Throwable ignored) {
					}
				}
			} finally {
				lock.writeLock().unlock();
			}
		}

		boolean isActive() {
			return active && !shuttingDown;
		}
	}

	private static long styledHwnd;
	private static long savedStyle;
	private static long savedExStyle;

	private static boolean librariesLoaded;
	private static WindowsLibrary winmmLib;
	private static WindowsLibrary dwmapiLib;
	private static WindowsLibrary kernel32Lib;

	private static long pTimeBeginPeriod;
	private static long pTimeEndPeriod;
	private static long pDwmSetWindowAttribute;
	private static long pGetCurrentProcess;
	private static long pSetProcessInformation;
	private static long pGetCurrentThread;
	private static long pSetThreadInformation;
	private static long pSetThreadPriority;
	private static long pCreateWaitableTimerExW;
	private static long pSetWaitableTimer;
	private static long pWaitForSingleObject;
	private static long pCloseHandle;

	private static volatile Thread renderThread;
	private static volatile boolean runtimeEnabled;
	private static volatile boolean highResTimerActive;
	private static volatile boolean powerThrottlingOptedOut;
	private static volatile boolean renderThreadHighQosActive;
	private static volatile boolean renderThreadPriorityBoosted;
	private static volatile boolean borderlessActive;
	private static volatile boolean exactBorderlessActive;
	private static volatile boolean shutdownHookRegistered;

	private static final WaitableTimerGuard WAIT_TIMER = new WaitableTimerGuard(new NativeTimerOps() {
		@Override
		public long createHandle() {
			if (pCreateWaitableTimerExW == 0L || pSetWaitableTimer == 0L || pWaitForSingleObject == 0L) {
				return 0L;
			}
			return JNI.invokePPP(0L, 0L, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_WAIT_ACCESS, pCreateWaitableTimerExW);
		}

		@Override
		public LongBuffer allocDueTimeBuffer() {
			return MemoryUtil.memAllocLong(1);
		}

		@Override
		public long bufferAddress(LongBuffer buf) {
			return MemoryUtil.memAddress(buf);
		}

		@Override
		public boolean waitNanos(long handle, long dueTimeAddr, long nanos) {
			long ticks100ns = nanos / 100L;
			if (ticks100ns <= 0L) {
				return true;
			}
			MemoryUtil.memPutLong(dueTimeAddr, -ticks100ns);
			int ok = JNI.invokePPPPI(handle, dueTimeAddr, 0, 0L, 0L, 0, pSetWaitableTimer);
			if (ok == 0) {
				return false;
			}
			int timeoutMs = (int) Math.min(2000L, Math.max(10L, (nanos / 1_000_000L) + 50L));
			int waitRes = JNI.invokePI(handle, timeoutMs, pWaitForSingleObject);
			return (waitRes & 0xFFFFFFFFL) == WAIT_OBJECT_0;
		}

		@Override
		public void closeHandle(long handle) {
			if (pCloseHandle != 0L) {
				JNI.invokePI(handle, pCloseHandle);
			}
		}

		@Override
		public void freeDueTimeBuffer(LongBuffer buf) {
			MemoryUtil.memFree(buf);
		}
	});

	private Win32DisplayRuntime() {}

	/** Called once during client startup on the render thread. */
	public static void init() {
		if (!IS_WINDOWS) {
			return;
		}
		renderThread = Thread.currentThread();
		ensureLibrariesLoaded();
		applyRuntimeSettings();
	}

	/**
	 * Applies or reverts the 1 ms timer resolution, high-resolution waitable timer usage, and
	 * Power Throttling / EcoQoS opt-out (process + render thread HighQoS) live from settings.
	 */
	public static synchronized void applyRuntimeSettings() {
		if (!IS_WINDOWS) {
			return;
		}
		ensureLibrariesLoaded();
		boolean enable = EphemeraCommon.config().windowsLowLatencyRuntime;
		runtimeEnabled = enable;
		if (enable) {
			enableHighResTimer();
			initHighResWaitTimer();
			optOutPowerThrottling();
			boostRenderThreadScheduling();
			registerShutdownHook();
		} else {
			disableHighResTimer();
			restoreRenderThreadScheduling();
			restorePowerThrottling();
		}
	}

	private static void ensureLibrariesLoaded() {
		if (librariesLoaded) {
			return;
		}
		librariesLoaded = true;
		winmmLib = tryLoad("winmm");
		if (winmmLib != null) {
			pTimeBeginPeriod = winmmLib.getFunctionAddress("timeBeginPeriod");
			pTimeEndPeriod = winmmLib.getFunctionAddress("timeEndPeriod");
		}
		dwmapiLib = tryLoad("dwmapi");
		if (dwmapiLib != null) {
			pDwmSetWindowAttribute = dwmapiLib.getFunctionAddress("DwmSetWindowAttribute");
		}
		kernel32Lib = tryLoad("kernel32");
		if (kernel32Lib != null) {
			pGetCurrentProcess = kernel32Lib.getFunctionAddress("GetCurrentProcess");
			pSetProcessInformation = kernel32Lib.getFunctionAddress("SetProcessInformation");
			pGetCurrentThread = kernel32Lib.getFunctionAddress("GetCurrentThread");
			pSetThreadInformation = kernel32Lib.getFunctionAddress("SetThreadInformation");
			pSetThreadPriority = kernel32Lib.getFunctionAddress("SetThreadPriority");
			pCreateWaitableTimerExW = kernel32Lib.getFunctionAddress("CreateWaitableTimerExW");
			pSetWaitableTimer = kernel32Lib.getFunctionAddress("SetWaitableTimer");
			pWaitForSingleObject = kernel32Lib.getFunctionAddress("WaitForSingleObject");
			pCloseHandle = kernel32Lib.getFunctionAddress("CloseHandle");
		}
	}

	private static WindowsLibrary tryLoad(String name) {
		try {
			return new WindowsLibrary(name);
		} catch (Throwable t) {
			LOGGER.debug("Optional Win32 library {} unavailable: {}", name, t.toString());
			return null;
		}
	}

	private static void enableHighResTimer() {
		if (highResTimerActive || pTimeBeginPeriod == 0L) {
			return;
		}
		try {
			int res = JNI.invokeI(1, pTimeBeginPeriod);
			if (res == 0) { // TIMERR_NOERROR
				highResTimerActive = true;
				LOGGER.info("Enabled Windows 1 ms high-resolution timer (timeBeginPeriod)");
			}
		} catch (Throwable t) {
			LOGGER.debug("timeBeginPeriod(1) failed: {}", t.toString());
		}
	}

	private static void disableHighResTimer() {
		if (!highResTimerActive || pTimeEndPeriod == 0L) {
			return;
		}
		highResTimerActive = false;
		try {
			JNI.invokeI(1, pTimeEndPeriod);
		} catch (Throwable ignored) {
		}
	}

	private static void optOutPowerThrottling() {
		if (powerThrottlingOptedOut || pGetCurrentProcess == 0L || pSetProcessInformation == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long hProcess = JNI.invokeP(pGetCurrentProcess);
			// Try both EXECUTION_SPEED and IGNORE_TIMER_RESOLUTION (Win11 22H2+); fall back to EXECUTION_SPEED alone on Win10.
			int combined = PROCESS_POWER_THROTTLING_EXECUTION_SPEED | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
			if (setPowerThrottling(stack, hProcess, combined, 0)
				|| setPowerThrottling(stack, hProcess, PROCESS_POWER_THROTTLING_EXECUTION_SPEED, 0)) {
				powerThrottlingOptedOut = true;
				LOGGER.info("Opted out of Windows Power Throttling / EcoQoS & timer-resolution throttling");
			}
		} catch (Throwable t) {
			LOGGER.debug("SetProcessInformation(ProcessPowerThrottling) unavailable: {}", t.toString());
		}
	}

	private static void restorePowerThrottling() {
		if (!powerThrottlingOptedOut || pGetCurrentProcess == 0L || pSetProcessInformation == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long hProcess = JNI.invokeP(pGetCurrentProcess);
			// ControlMask=0, StateMask=0 returns process power throttling control to OS defaults.
			if (setPowerThrottling(stack, hProcess, 0, 0)) {
				powerThrottlingOptedOut = false;
				LOGGER.info("Restored default Windows Power Throttling / EcoQoS policy");
			}
		} catch (Throwable t) {
			LOGGER.debug("Could not restore Windows Power Throttling policy: {}", t.toString());
		}
	}

	private static boolean setPowerThrottling(MemoryStack stack, long hProcess, int controlMask, int stateMask) {
		IntBuffer state = stack.callocInt(3);
		state.put(0, PROCESS_POWER_THROTTLING_CURRENT_VERSION);
		state.put(1, controlMask);
		state.put(2, stateMask);
		long addr = MemoryUtil.memAddress(state);
		return JNI.invokePPI(hProcess, PROCESS_POWER_THROTTLING, addr, 12, pSetProcessInformation) != 0;
	}

	/**
	 * Marks the render thread as HighQoS ({@code ThreadPowerThrottling} disabled) and
	 * {@code THREAD_PRIORITY_ABOVE_NORMAL} so Windows 11 hybrid CPU schedulers (Intel P/E cores,
	 * AMD X3D) keep rendering on performance cores even when unfocused or on a secondary monitor.
	 */
	private static void boostRenderThreadScheduling() {
		if (Thread.currentThread() != renderThread || pGetCurrentThread == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long hThread = JNI.invokeP(pGetCurrentThread);
			if (!renderThreadHighQosActive && pSetThreadInformation != 0L) {
				if (setThreadPowerThrottling(stack, hThread, THREAD_POWER_THROTTLING_EXECUTION_SPEED, 0)) {
					renderThreadHighQosActive = true;
				}
			}
			if (!renderThreadPriorityBoosted && pSetThreadPriority != 0L) {
				if (JNI.invokePI(hThread, THREAD_PRIORITY_ABOVE_NORMAL, pSetThreadPriority) != 0) {
					renderThreadPriorityBoosted = true;
				}
			}
			if (renderThreadHighQosActive || renderThreadPriorityBoosted) {
				LOGGER.info("Enabled render-thread HighQoS P-core scheduling (threadHighQoS={}, aboveNormalPriority={})",
					renderThreadHighQosActive, renderThreadPriorityBoosted);
			}
		} catch (Throwable t) {
			LOGGER.debug("Render-thread HighQoS scheduling unavailable: {}", t.toString());
		}
	}

	private static void restoreRenderThreadScheduling() {
		if (Thread.currentThread() != renderThread || pGetCurrentThread == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long hThread = JNI.invokeP(pGetCurrentThread);
			if (renderThreadHighQosActive && pSetThreadInformation != 0L) {
				if (setThreadPowerThrottling(stack, hThread, 0, 0)) {
					renderThreadHighQosActive = false;
				}
			}
			if (renderThreadPriorityBoosted && pSetThreadPriority != 0L) {
				if (JNI.invokePI(hThread, THREAD_PRIORITY_NORMAL, pSetThreadPriority) != 0) {
					renderThreadPriorityBoosted = false;
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static boolean setThreadPowerThrottling(MemoryStack stack, long hThread, int controlMask, int stateMask) {
		IntBuffer state = stack.callocInt(3);
		state.put(0, THREAD_POWER_THROTTLING_CURRENT_VERSION);
		state.put(1, controlMask);
		state.put(2, stateMask);
		long addr = MemoryUtil.memAddress(state);
		return JNI.invokePPI(hThread, THREAD_POWER_THROTTLING, addr, 12, pSetThreadInformation) != 0;
	}

	/**
	 * Marks a latency-critical worker thread (such as {@code Ephemera-VkPresent}) as HighQoS so
	 * Windows 11 hybrid schedulers never demote present/submit calls onto an E-core. Fails soft.
	 */
	public static void boostCurrentThreadHighQoS() {
		if (!IS_WINDOWS || !EphemeraCommon.config().windowsLowLatencyRuntime) {
			return;
		}
		ensureLibrariesLoaded();
		if (pGetCurrentThread == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long hThread = JNI.invokeP(pGetCurrentThread);
			if (pSetThreadInformation != 0L) {
				setThreadPowerThrottling(stack, hThread, THREAD_POWER_THROTTLING_EXECUTION_SPEED, 0);
			}
			if (pSetThreadPriority != 0L) {
				JNI.invokePI(hThread, THREAD_PRIORITY_ABOVE_NORMAL, pSetThreadPriority);
			}
		} catch (Throwable ignored) {
		}
	}

	private static void initHighResWaitTimer() {
		if (WAIT_TIMER.isActive()) {
			return;
		}
		if (WAIT_TIMER.init(Thread.currentThread())) {
			LOGGER.info("Enabled Windows high-resolution waitable timer (CREATE_WAITABLE_TIMER_HIGH_RESOLUTION)");
		}
	}

	/**
	 * Sleeps the render thread for {@code nanos} using Windows' 0.5 ms high-resolution waitable
	 * timer when active, eliminating {@code LockSupport.parkNanos}'s 1-2.5 ms Windows tick
	 * oversleeps while yielding the CPU cleanly to capture/encoding processes (OBS, Discord).
	 */
	public static void parkNanos(long nanos) {
		if (nanos <= 0L) {
			return;
		}
		if (runtimeEnabled && EphemeraCommon.config().windowsLowLatencyRuntime) {
			long deadline = System.nanoTime() + nanos;
			if (WAIT_TIMER.tryParkNanos(nanos, Thread.currentThread())) {
				return;
			}
			long remaining = deadline - System.nanoTime();
			if (remaining > 0L) {
				LockSupport.parkNanos(remaining);
			}
			return;
		}
		LockSupport.parkNanos(nanos);
	}

	private static synchronized void shutdownCleanup() {
		runtimeEnabled = false;
		disableHighResTimer();
		restorePowerThrottling();
		WAIT_TIMER.shutdown(2500L);
	}

	private static void registerShutdownHook() {
		if (shutdownHookRegistered) {
			return;
		}
		shutdownHookRegistered = true;
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(Win32DisplayRuntime::shutdownCleanup, "Ephemera-Win32Cleanup"));
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Records the borderless fullscreen state of the game window. An exact-size ({@code flip})
	 * window also loses the Windows 11 rounded corners and DWM border: neither is visible on a
	 * monitor-sized window, but either can keep DWM from promoting it to Independent Flip.
	 */
	public static void onBorderlessChanged(long hwnd, boolean borderless, boolean flip) {
		onBorderlessChanged(hwnd, borderless, flip, 0, 0, 0, 0);
	}

	/**
	 * Records borderless fullscreen state and, when exact-bounds Independent Flip is enabled,
	 * strips lingering Win32 frame/border styles, disables Windows 11 rounded corners and DWM
	 * border halo, and commits the exact monitor rectangle via {@code SetWindowPos(SWP_FRAMECHANGED)}.
	 */
	public static void onBorderlessChanged(long hwnd, boolean borderless, boolean flip,
										   int x, int y, int width, int height) {
		borderlessActive = borderless;
		boolean exact = borderless && flip;
		boolean changed = exact != exactBorderlessActive;
		exactBorderlessActive = exact;
		if (!IS_WINDOWS || hwnd == 0L) {
			return;
		}
		ensureLibrariesLoaded();
		if (exact) {
			try {
				long style = User32.GetWindowLongPtr(null, hwnd, User32.GWL_STYLE);
				long exStyle = User32.GetWindowLongPtr(null, hwnd, User32.GWL_EXSTYLE);
				if (styledHwnd != hwnd) {
					styledHwnd = hwnd;
					savedStyle = style;
					savedExStyle = exStyle;
				}
				long cleanStyle = (style & ~BORDERLESS_STRIP_STYLE)
					| (User32.WS_POPUP & 0xFFFFFFFFL)
					| User32.WS_CLIPSIBLINGS
					| User32.WS_CLIPCHILDREN;
				long cleanExStyle = exStyle & ~BORDERLESS_STRIP_EXSTYLE;
				if (cleanStyle != style) {
					User32.SetWindowLongPtr(null, hwnd, User32.GWL_STYLE, cleanStyle);
				}
				if (cleanExStyle != exStyle) {
					User32.SetWindowLongPtr(null, hwnd, User32.GWL_EXSTYLE, cleanExStyle);
				}
				setDwmIntAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND);
				setDwmIntAttribute(hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE);
				if (width > 0 && height > 0) {
					User32.SetWindowPos(
						null, hwnd, 0L, x, y, width, height,
						User32.SWP_FRAMECHANGED | User32.SWP_NOZORDER | User32.SWP_NOOWNERZORDER | User32.SWP_NOACTIVATE
					);
				}
				if (changed) {
					LOGGER.info("Borderless fullscreen sized to exact monitor bounds ({}x{} @ {},{}) for Independent Flip",
						width, height, x, y);
				}
			} catch (Throwable t) {
				LOGGER.warn("Could not apply Win32 borderless flip attributes: {}", t.toString());
			}
		} else if (changed || styledHwnd == hwnd) {
			setDwmIntAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DEFAULT);
			setDwmIntAttribute(hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_DEFAULT);
			if (styledHwnd == hwnd) {
				try {
					long curStyle = User32.GetWindowLongPtr(null, hwnd, User32.GWL_STYLE);
					long curExStyle = User32.GetWindowLongPtr(null, hwnd, User32.GWL_EXSTYLE);
					long restoredStyle = (curStyle & ~(User32.WS_POPUP & 0xFFFFFFFFL)) | (savedStyle & BORDERLESS_STRIP_STYLE);
					long restoredExStyle = curExStyle | (savedExStyle & BORDERLESS_STRIP_EXSTYLE);
					if (restoredStyle != curStyle) {
						User32.SetWindowLongPtr(null, hwnd, User32.GWL_STYLE, restoredStyle);
					}
					if (restoredExStyle != curExStyle) {
						User32.SetWindowLongPtr(null, hwnd, User32.GWL_EXSTYLE, restoredExStyle);
					}
				} catch (Throwable ignored) {
				}
				styledHwnd = 0L;
			}
		}
	}

	private static void setDwmIntAttribute(long hwnd, int attribute, int value) {
		if (pDwmSetWindowAttribute == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer buf = stack.ints(value);
			JNI.invokePPI(hwnd, attribute, MemoryUtil.memAddress(buf), 4, pDwmSetWindowAttribute);
		} catch (Throwable ignored) {
		}
	}

	public static boolean isHighResTimerActive() {
		return runtimeEnabled && (highResTimerActive || WAIT_TIMER.isActive());
	}

	public static boolean isHighResWaitTimerActive() {
		return runtimeEnabled && WAIT_TIMER.isActive();
	}

	public static boolean isBorderlessActive() {
		return borderlessActive;
	}

	public static boolean isExactBorderlessActive() {
		return exactBorderlessActive;
	}

	public static String diagnosticsSummary() {
		if (!IS_WINDOWS) {
			return "Non-Windows (native defaults)";
		}
		List<String> parts = new ArrayList<>(4);
		if (isHighResWaitTimerActive()) parts.add("HiResWait");
		else if (runtimeEnabled && highResTimerActive) parts.add("1ms timer");
		if (powerThrottlingOptedOut) parts.add("No-EcoQoS");
		if (exactBorderlessActive) parts.add("Exact Flip");
		return parts.isEmpty() ? "Standard" : String.join(" · ", parts);
	}
}
