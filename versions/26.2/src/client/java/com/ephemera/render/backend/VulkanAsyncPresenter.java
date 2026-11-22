package com.ephemera.render.backend;

import com.mojang.blaze3d.vulkan.VulkanQueue;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated single-thread asynchronous Vulkan queue submitter, swapchain presenter and
 * pre-acquirer.
 *
 * <p>On Windows DWM / WDDM, {@code vkQueueSubmit2KHR} (~70-130 us) and {@code vkQueuePresentKHR}
 * (~250 us) block the calling CPU thread inside kernel transitions while the render thread has
 * next-frame CPU work ready. Both are handed, in exact program order, to one FIFO worker thread
 * ({@code Ephemera-VkPresent}) through a lock-free single-producer / single-consumer job ring:
 * <ul>
 *   <li>{@code JOB_SUBMIT}: {@code VulkanQueue.Submission.close()} ({@code vkQueueSubmit2KHR}).</li>
 *   <li>{@code JOB_PRESENT_AND_ACQUIRE}: {@code vkQueuePresentKHR} + optional next-frame
 *       {@code vkAcquireNextImageKHR}.</li>
 * </ul>
 *
 * <p>Vulkan external synchronization is preserved because only the worker touches
 * {@code VkQueue} / {@code VkSwapchainKHR} while jobs are queued; the render thread drains the
 * ring ({@link #awaitIdle()}) before any direct queue/swapchain use. {@code head} advances only
 * after a job has fully finished, so {@code head == tail} is exactly "worker idle".
 */
public final class VulkanAsyncPresenter {
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/VulkanPresent");
	private static final boolean DISABLED = Boolean.getBoolean("ephemera.vulkan.syncPresent");
	private static final boolean SYNC_SUBMIT = Boolean.getBoolean("ephemera.vulkan.syncSubmit");

	private static final int RING_CAP = 8;
	private static final int RING_MASK = RING_CAP - 1;

	private static final int JOB_SUBMIT = 1;
	private static final int JOB_PRESENT_AND_ACQUIRE = 2;

	private static final int COND_IDLE = 0;
	private static final int COND_SUBMITTED = 1;
	private static final int COND_PRESENT_DONE = 2;
	private static final int COND_RING_SPACE = 3;

	/** Spin this long before parking so sub-80 us waits never pay OS wakeup jitter. */
	private static final long SPIN_NS = 80_000L;
	/** Safety-net park bound; every job completion unparks the waiter explicitly. */
	private static final long PARK_NS = 1_000_000L;

	/** Consumer index: advanced only after the job at {@code head & RING_MASK} has completed. */
	private static volatile long head = 0L;
	/** Producer index: advanced after the slot at {@code tail & RING_MASK} is fully written. */
	private static volatile long tail = 0L;
	private static volatile Thread worker;
	private static volatile Thread renderWaiter;

	private static volatile long lastSubmittedIndex = Long.MIN_VALUE;
	private static volatile Throwable asyncError;
	private static boolean uncappedPresent;
	private static volatile boolean presentAcquirePending;
	private static volatile boolean presentAcquireDone;

	// Ring slots (published by the volatile tail write, released by the volatile head write)
	private static final int[] slotType = new int[RING_CAP];
	private static final VulkanQueue.Submission[] slotSubmission = new VulkanQueue.Submission[RING_CAP];
	private static final long[] slotSubmitIndex = new long[RING_CAP];
	private static final VkDevice[] slotDevice = new VkDevice[RING_CAP];
	private static final VkQueue[] slotQueue = new VkQueue[RING_CAP];
	private static final long[] slotSwapchain = new long[RING_CAP];
	private static final long[] slotPresentSem = new long[RING_CAP];
	private static final int[] slotImageIndex = new int[RING_CAP];
	private static final boolean[] slotPreAcquire = new boolean[RING_CAP];
	private static final long[] slotAcquireSem = new long[RING_CAP];

	// Worker-owned persistent native block: VkPresentInfoKHR + pWaitSemaphores + pSwapchains + pImageIndices + acquire out index
	private static final int PRESENT_SEM_OFFSET = VkPresentInfoKHR.SIZEOF;
	private static final int PRESENT_SWAPCHAIN_OFFSET = PRESENT_SEM_OFFSET + 8;
	private static final int PRESENT_INDEX_OFFSET = PRESENT_SWAPCHAIN_OFFSET + 8;
	private static final int ACQUIRE_INDEX_OFFSET = PRESENT_INDEX_OFFSET + 4;
	private static final int PRESENT_BLOCK_SIZE = ACQUIRE_INDEX_OFFSET + 4;
	private static long presentBlock;

	// Job outputs (published before presentAcquireDone = true / head advance)
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

	/**
	 * Async submit only pays off when present is async too (uncapped modes). Under VSync the
	 * vanilla present drains the queue right after each submit, so queuing would only add the
	 * render and worker spin. Submits issued directly always drain queued jobs first.
	 */
	public static boolean isAsyncSubmitEnabled() {
		return !SYNC_SUBMIT && uncappedPresent && isEnabled();
	}

	/** Render thread: the surface was configured with an uncapped (IMMEDIATE/MAILBOX) present mode. */
	public static void setUncappedPresent(boolean uncapped) {
		uncappedPresent = uncapped;
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
	 * Queues {@code submission.close()} ({@code vkQueueSubmit2KHR}) behind all earlier jobs.
	 * Render thread only. The submission must not be touched again by the caller.
	 */
	public static void enqueueSubmit(VulkanQueue.Submission submission, long submitIndex) {
		checkAsyncError();
		long t = tail;
		if (t - head >= RING_CAP) {
			awaitCondition(COND_RING_SPACE, 0L);
		}
		int i = (int) (t & RING_MASK);
		slotType[i] = JOB_SUBMIT;
		slotSubmission[i] = submission;
		slotSubmitIndex[i] = submitIndex;
		tail = t + 1;
		LockSupport.unpark(ensureWorker());
	}

	/**
	 * Queues {@code vkQueuePresentKHR} (and optionally {@code vkAcquireNextImageKHR} for the next
	 * frame) behind all earlier jobs. Render thread only; the caller must not touch
	 * {@code presentQueue} or {@code swapchain} until {@link #harvest()} / {@link #awaitIdle()}.
	 */
	public static void dispatch(VkDevice device, VkQueue presentQueue, long swapchain,
								long presentSemaphore, int presentImageIndex,
								boolean preAcquire, long nextAcquireSemaphore) {
		checkAsyncError();
		if (presentAcquirePending) {
			// Unharvested earlier present: let it finish so its done flag cannot be mistaken for this job's.
			awaitCondition(COND_PRESENT_DONE, 0L);
		}
		presentAcquirePending = true;
		presentAcquireDone = false;
		long t = tail;
		if (t - head >= RING_CAP) {
			awaitCondition(COND_RING_SPACE, 0L);
		}
		int i = (int) (t & RING_MASK);
		slotType[i] = JOB_PRESENT_AND_ACQUIRE;
		slotDevice[i] = device;
		slotQueue[i] = presentQueue;
		slotSwapchain[i] = swapchain;
		slotPresentSem[i] = presentSemaphore;
		slotImageIndex[i] = presentImageIndex;
		slotPreAcquire[i] = preAcquire;
		slotAcquireSem[i] = nextAcquireSemaphore;
		tail = t + 1;
		LockSupport.unpark(ensureWorker());
	}

	public static long lastSubmittedIndex() {
		return lastSubmittedIndex;
	}

	/** Resets the submitted-index watermark when a {@code VulkanCommandEncoder} is destroyed. */
	public static void resetSubmittedIndex() {
		lastSubmittedIndex = Long.MIN_VALUE;
	}

	/** True once {@code vkQueueSubmit2KHR} has been issued for {@code submitIndex} (or nothing is queued). */
	public static boolean isSubmitted(long submitIndex) {
		return lastSubmittedIndex >= submitIndex || head == tail;
	}

	/** Waits until {@code vkQueueSubmit2KHR} has been issued for {@code targetSubmitIndex}. */
	public static void awaitSubmitted(long targetSubmitIndex) {
		if (!isSubmitted(targetSubmitIndex)) {
			awaitCondition(COND_SUBMITTED, targetSubmitIndex);
		}
		checkAsyncError();
	}

	/** Waits until every queued job has finished touching {@code VkQueue} / {@code VkSwapchainKHR}. */
	public static void awaitIdle() {
		if (head != tail) {
			awaitCondition(COND_IDLE, 0L);
		}
		checkAsyncError();
	}

	/** Drains all jobs and discards any unconsumed pre-acquired result (on resize/destroy). */
	public static void awaitAndReset() {
		try {
			awaitIdle();
		} finally {
			presentAcquirePending = false;
			presentAcquireDone = false;
			outAcquired = false;
		}
	}

	public static boolean hasResult() {
		return presentAcquirePending;
	}

	/**
	 * Waits only for the pending present + pre-acquire job (not for later queued submits);
	 * outputs remain readable by the render thread afterwards.
	 */
	public static void harvest() {
		awaitCondition(COND_PRESENT_DONE, 0L);
		presentAcquirePending = false;
		presentAcquireDone = false;
		checkAsyncError();
	}

	public static int presentResult() { return outPresentResult; }
	public static boolean acquired() { return outAcquired; }
	public static int acquireResult() { return outAcquireResult; }
	public static int acquiredImageIndex() { return outAcquiredImageIndex; }
	public static long presentNs() { return outPresentNs; }
	public static long acquireNs() { return outAcquireNs; }

	private static void checkAsyncError() {
		Throwable err = asyncError;
		if (err == null) {
			return;
		}
		asyncError = null;
		if (err instanceof RuntimeException re) {
			throw re;
		}
		if (err instanceof Error e) {
			throw e;
		}
		throw new RuntimeException("Async Vulkan queue submission failed", err);
	}

	private static boolean conditionMet(int cond, long arg) {
		return switch (cond) {
			case COND_SUBMITTED -> lastSubmittedIndex >= arg || head == tail;
			case COND_PRESENT_DONE -> presentAcquireDone || head == tail;
			case COND_RING_SPACE -> tail - head < RING_CAP;
			default -> head == tail;
		};
	}

	/**
	 * Spin-then-park wait. {@code renderWaiter} is published before the condition is rechecked,
	 * and the worker reads it after its volatile state writes, so a wakeup can never be lost.
	 */
	private static void awaitCondition(int cond, long arg) {
		renderWaiter = Thread.currentThread();
		try {
			long start = System.nanoTime();
			int spins = 0;
			boolean park = false;
			while (!conditionMet(cond, arg)) {
				if (park) {
					Thread w = worker;
					if (w == null || !w.isAlive()) {
						// Crash instead of freezing the render thread forever on a dead worker.
						throw new IllegalStateException("Ephemera Vulkan submit worker is not running", asyncError);
					}
					LockSupport.parkNanos(VulkanAsyncPresenter.class, PARK_NS);
				} else {
					Thread.onSpinWait();
					if ((++spins & 31) == 0 && System.nanoTime() - start >= SPIN_NS) {
						park = true;
					}
				}
			}
		} finally {
			renderWaiter = null;
		}
	}

	/** Brief worker spin before parking so back-to-back uncapped submits skip OS unpark jitter. */
	private static final long WORKER_SPIN_NS = 40_000L;

	private static void runLoop() {
		while (true) {
			long h = head;
			if (h == tail) {
				long spinStart = System.nanoTime();
				int spins = 0;
				while (h == tail) {
					Thread.onSpinWait();
					if ((++spins & 31) == 0 && System.nanoTime() - spinStart >= WORKER_SPIN_NS) {
						LockSupport.park(VulkanAsyncPresenter.class);
						break;
					}
				}
				continue;
			}
			int i = (int) (h & RING_MASK);
			if (slotType[i] == JOB_SUBMIT) {
				runSubmit(i);
			} else {
				runPresentAndAcquire(i);
			}
			head = h + 1;
			Thread waiter = renderWaiter;
			if (waiter != null) {
				LockSupport.unpark(waiter);
			}
		}
	}

	private static void runSubmit(int i) {
		VulkanQueue.Submission submission = slotSubmission[i];
		long submitIndex = slotSubmitIndex[i];
		slotSubmission[i] = null;
		long t0 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
		try {
			submission.close();
		} catch (Throwable t) {
			LOGGER.error("Async vkQueueSubmit2KHR failed for submit index {}", submitIndex, t);
			if (asyncError == null) {
				asyncError = t;
			}
		}
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.recordWorkerSubmit(System.nanoTime() - t0);
		}
		lastSubmittedIndex = submitIndex;
	}

	private static void runPresentAndAcquire(int i) {
		VkDevice device = slotDevice[i];
		VkQueue queue = slotQueue[i];
		slotDevice[i] = null;
		slotQueue[i] = null;
		try {
			executePresentAndAcquire(device, queue, slotSwapchain[i], slotPresentSem[i], slotImageIndex[i],
				slotPreAcquire[i], slotAcquireSem[i]);
		} catch (Throwable t) {
			LOGGER.error("Unexpected failure in VulkanAsyncPresenter", t);
			outPresentResult = VK12.VK_ERROR_DEVICE_LOST;
			outAcquired = false;
			outAcquireResult = VK12.VK_ERROR_DEVICE_LOST;
			outAcquiredImageIndex = -1;
		}
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.recordWorkerPresentAcquire(outPresentNs, outAcquireNs);
		}
		presentAcquireDone = true;
	}

	private static void executePresentAndAcquire(VkDevice device, VkQueue queue, long swapchain, long presentSem,
												 int imageIndex, boolean preAcquire, long acquireSem) {
		long block = presentBlock;
		if (block == 0L) {
			block = MemoryUtil.nmemCalloc(1, PRESENT_BLOCK_SIZE);
			if (block == 0L) {
				throw new OutOfMemoryError("VulkanAsyncPresenter present block");
			}
			MemoryUtil.memPutInt(block + VkPresentInfoKHR.STYPE, KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
			MemoryUtil.memPutInt(block + VkPresentInfoKHR.WAITSEMAPHORECOUNT, 1);
			MemoryUtil.memPutAddress(block + VkPresentInfoKHR.PWAITSEMAPHORES, block + PRESENT_SEM_OFFSET);
			MemoryUtil.memPutInt(block + VkPresentInfoKHR.SWAPCHAINCOUNT, 1);
			MemoryUtil.memPutAddress(block + VkPresentInfoKHR.PSWAPCHAINS, block + PRESENT_SWAPCHAIN_OFFSET);
			MemoryUtil.memPutAddress(block + VkPresentInfoKHR.PIMAGEINDICES, block + PRESENT_INDEX_OFFSET);
			presentBlock = block;
		}
		MemoryUtil.memPutLong(block + PRESENT_SEM_OFFSET, presentSem);
		MemoryUtil.memPutLong(block + PRESENT_SWAPCHAIN_OFFSET, swapchain);
		MemoryUtil.memPutInt(block + PRESENT_INDEX_OFFSET, imageIndex);

		long t0 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
		int presRes = KHRSwapchain.nvkQueuePresentKHR(queue, block);
		long t1 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
		outPresentResult = presRes;
		outPresentNs = t1 - t0;

		if (preAcquire && presRes == VK12.VK_SUCCESS) {
			long pIndex = block + ACQUIRE_INDEX_OFFSET;
			MemoryUtil.memPutInt(pIndex, -1);
			int acqRes = KHRSwapchain.nvkAcquireNextImageKHR(device, swapchain, 5_000_000_000L, acquireSem, 0L, pIndex);
			long t2 = VulkanDiagTracker.ENABLED ? System.nanoTime() : 0L;
			outAcquired = true;
			outAcquireResult = acqRes;
			outAcquiredImageIndex = MemoryUtil.memGetInt(pIndex);
			outAcquireNs = t2 - t1;
		} else {
			outAcquired = false;
			outAcquireResult = VK12.VK_SUCCESS;
			outAcquiredImageIndex = -1;
			outAcquireNs = 0L;
		}
	}
}
