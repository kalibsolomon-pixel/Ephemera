package com.ephemera.render.backend;

import java.nio.IntBuffer;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated single-thread asynchronous Vulkan swapchain presenter and pre-acquirer for uncapped
 * presentation ({@code IMMEDIATE} / {@code MAILBOX}).
 *
 * <p>On Windows DWM / WDDM, {@code vkQueuePresentKHR} blocks the calling CPU thread for ~250 us
 * inside the kernel composition path while the render thread has ~500-750 us of pure CPU game
 * and command-buffer work ready for the next frame. Handing {@code vkQueuePresentKHR} followed
 * immediately by {@code vkAcquireNextImageKHR} to a dedicated worker thread overlaps the entire
 * ~290 us WSI cost with next-frame CPU rendering while preserving strict Vulkan external
 * synchronization:
 * <ul>
 *   <li>Only this thread touches {@code VkQueue} and {@code VkSwapchainKHR} while a job is
 *       {@link #STATE_RUNNING}.</li>
 *   <li>The render thread awaits completion before any {@code vkQueueSubmit2KHR},
 *       {@code vkQueueWaitIdle}, swapchain reconfigure/destroy, or {@code blitFromTexture}.</li>
 * </ul>
 */
public final class VulkanAsyncPresenter {
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/VulkanPresent");
	private static final boolean DISABLED = Boolean.getBoolean("ephemera.vulkan.syncPresent");

	private static final int STATE_IDLE = 0;
	private static final int STATE_RUNNING = 1;
	private static final int STATE_DONE = 2;

	private static volatile int state = STATE_IDLE;
	private static volatile Thread worker;
	private static volatile Thread renderWaiter;

	// Job inputs (published before state = STATE_RUNNING)
	private static VkDevice jobDevice;
	private static VkQueue jobQueue;
	private static long jobSwapchain;
	private static long jobPresentSem;
	private static int jobPresentImageIndex;
	private static boolean jobPreAcquire;
	private static long jobAcquireSem;

	// Job outputs (published before state = STATE_DONE)
	private static int outPresentResult;
	private static boolean outAcquired;
	private static int outAcquireResult;
	private static int outAcquiredImageIndex;
	private static long outPresentNs;
	private static long outAcquireNs;

	private VulkanAsyncPresenter() {}

	public static boolean isEnabled() {
		return !DISABLED && VulkanModernCapabilities.modernSyncActive();
	}

	private static Thread ensureWorker() {
		Thread t = worker;
		if (t != null && t.isAlive()) {
			return t;
		}
		synchronized (VulkanAsyncPresenter.class) {
			t = worker;
			if (t != null && t.isAlive()) {
				return t;
			}
			t = new Thread(VulkanAsyncPresenter::runLoop, "Ephemera-VkPresent");
			t.setDaemon(true);
			t.setPriority(Thread.MAX_PRIORITY);
			t.start();
			worker = t;
			return t;
		}
	}

	/**
	 * Dispatches {@code vkQueuePresentKHR} (and optionally {@code vkAcquireNextImageKHR} for the
	 * next frame) onto the dedicated present thread. Caller must be the render thread and must not
	 * touch {@code presentQueue} or {@code swapchain} until {@link #awaitIdle()} returns.
	 */
	public static void dispatch(VkDevice device, VkQueue presentQueue, long swapchain,
								long presentSemaphore, int presentImageIndex,
								boolean preAcquire, long nextAcquireSemaphore) {
		awaitIdle();
		jobDevice = device;
		jobQueue = presentQueue;
		jobSwapchain = swapchain;
		jobPresentSem = presentSemaphore;
		jobPresentImageIndex = presentImageIndex;
		jobPreAcquire = preAcquire;
		jobAcquireSem = nextAcquireSemaphore;
		state = STATE_RUNNING;
		LockSupport.unpark(ensureWorker());
	}

	/**
	 * Waits until any in-flight async present/pre-acquire has finished touching {@code VkQueue}
	 * and {@code VkSwapchainKHR}. Preserves {@link #STATE_DONE} so {@code blitFromTexture} can
	 * still harvest the pre-acquired swapchain image.
	 */
	public static void awaitIdle() {
		if (state != STATE_RUNNING) {
			return;
		}
		Thread self = Thread.currentThread();
		renderWaiter = self;
		try {
			// Spin briefly first (~20 us) to avoid OS park/unpark jitter when almost done
			int spins = 0;
			while (state == STATE_RUNNING) {
				if (++spins < 200) {
					Thread.onSpinWait();
				} else {
					LockSupport.park();
				}
			}
		} finally {
			renderWaiter = null;
		}
	}

	/** Waits for any in-flight job and discards any unconsumed pre-acquired result (on resize/destroy). */
	public static void awaitAndReset() {
		awaitIdle();
		state = STATE_IDLE;
		outAcquired = false;
	}

	public static boolean hasResult() {
		return state != STATE_IDLE;
	}

	/** Waits if still running and transitions {@code DONE -> IDLE}; outputs remain readable by render thread. */
	public static void harvest() {
		awaitIdle();
		state = STATE_IDLE;
	}

	public static int presentResult() { return outPresentResult; }
	public static boolean acquired() { return outAcquired; }
	public static int acquireResult() { return outAcquireResult; }
	public static int acquiredImageIndex() { return outAcquiredImageIndex; }
	public static long presentNs() { return outPresentNs; }
	public static long acquireNs() { return outAcquireNs; }

	private static void runLoop() {
		while (true) {
			if (state != STATE_RUNNING) {
				LockSupport.park();
				continue;
			}
			try {
				executeJob();
			} catch (Throwable t) {
				LOGGER.error("Unexpected failure in VulkanAsyncPresenter", t);
				outPresentResult = VK12.VK_ERROR_DEVICE_LOST;
				outAcquired = false;
				outAcquireResult = VK12.VK_ERROR_DEVICE_LOST;
				outAcquiredImageIndex = -1;
			} finally {
				state = STATE_DONE;
				Thread waiter = renderWaiter;
				if (waiter != null) {
					LockSupport.unpark(waiter);
				}
			}
		}
	}

	private static void executeJob() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long t0 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
			VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
				.sType$Default()
				.pWaitSemaphores(stack.longs(jobPresentSem))
				.swapchainCount(1)
				.pSwapchains(stack.longs(jobSwapchain))
				.pImageIndices(stack.ints(jobPresentImageIndex));
			int presRes = KHRSwapchain.vkQueuePresentKHR(jobQueue, presentInfo);
			long t1 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
			outPresentResult = presRes;
			outPresentNs = t1 - t0;

			if (jobPreAcquire && presRes == VK12.VK_SUCCESS) {
				IntBuffer pIndex = stack.ints(-1);
				int acqRes = KHRSwapchain.vkAcquireNextImageKHR(
					jobDevice, jobSwapchain, 5_000_000_000L, jobAcquireSem, 0L, pIndex);
				long t2 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
				outAcquired = true;
				outAcquireResult = acqRes;
				outAcquiredImageIndex = pIndex.get(0);
				outAcquireNs = t2 - t1;
			} else {
				outAcquired = false;
				outAcquireResult = VK12.VK_SUCCESS;
				outAcquiredImageIndex = -1;
				outAcquireNs = 0L;
			}
		}
	}
}
