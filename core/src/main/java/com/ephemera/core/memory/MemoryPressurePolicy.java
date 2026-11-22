package com.ephemera.core.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java native-buffer shrink policy and leak-prevention telemetry shared by common and client
 * memory managers.
 *
 * <p>Minecraft's off-heap {@code ByteBufferBuilder} and Vulkan staging buffers grow dynamically on
 * peak frames (large chunk bursts, dense entity scenes, high-res GUI batches) and never shrink on
 * their own, silently holding tens to hundreds of megabytes of native RSS for the rest of a session.
 * This policy computes when a buffer has been persistently under-utilized and safely picks a
 * shrunk target capacity without causing grow/shrink thrashing.
 */
public final class MemoryPressurePolicy {
	/** Default native buffer capacity above which idle shrinking is considered (4 MiB). */
	public static final long DEFAULT_NATIVE_SHRINK_THRESHOLD_BYTES = 4L * 1024L * 1024L;
	/** Default consecutive low-utilization clears required before shrinking a native buffer. */
	public static final int DEFAULT_IDLE_CLEARS_BEFORE_SHRINK = 120;
	/** Default Vulkan staging ring baseline capacity (4 MiB). */
	public static final long DEFAULT_STAGING_BASELINE_BYTES = 4L * 1024L * 1024L;

	private static final AtomicLong RECLAIMED_NATIVE_BYTES = new AtomicLong();
	private static final AtomicLong RECLAIMED_HEAP_ESTIMATE_BYTES = new AtomicLong();
	private static final AtomicLong DEDUP_SAVED_OBJECTS = new AtomicLong();
	private static final AtomicLong WORLD_UNLOAD_SWEEPS = new AtomicLong();
	private static final AtomicLong RESOURCE_RELOAD_SWEEPS = new AtomicLong();

	private MemoryPressurePolicy() {}

	/**
	 * Per-buffer tracker that decides when an oversized native buffer should shrink back down.
	 * Zero allocations; intended to be embedded directly inside a buffer mixin or manager.
	 */
	public static final class NativeBufferShrinkTracker {
		private final long baselineBytes;
		private final long shrinkThresholdBytes;
		private final int idleClearsRequired;
		private long recentPeakBytes;
		private int idleClearStreak;

		public NativeBufferShrinkTracker(long baselineBytes, long shrinkThresholdBytes, int idleClearsRequired) {
			this.baselineBytes = Math.max(4096L, baselineBytes);
			this.shrinkThresholdBytes = Math.max(this.baselineBytes * 2L, shrinkThresholdBytes);
			this.idleClearsRequired = Math.max(16, idleClearsRequired);
		}

		/**
		 * Records a completed buffer lifecycle (clear/discard) with its high-water usage since the
		 * previous clear. Returns the target shrunk capacity in bytes when shrinking is advised,
		 * or {@code 0L} when current capacity should be kept.
		 */
		public long onClear(long currentCapacity, long usedBytesThisCycle) {
			if (usedBytesThisCycle > recentPeakBytes) {
				recentPeakBytes = usedBytesThisCycle;
			}
			if (currentCapacity <= shrinkThresholdBytes) {
				idleClearStreak = 0;
				recentPeakBytes = 0L;
				return 0L;
			}
			// If this cycle used more than 35% of capacity, the allocation is genuinely needed.
			if (usedBytesThisCycle * 100L >= currentCapacity * 35L) {
				idleClearStreak = 0;
				recentPeakBytes = 0L;
				return 0L;
			}
			idleClearStreak++;
			if (idleClearStreak < idleClearsRequired) {
				return 0L;
			}
			long desired = nextPowerOfTwo(Math.max(baselineBytes, recentPeakBytes * 2L));
			idleClearStreak = 0;
			recentPeakBytes = 0L;
			if (desired < currentCapacity) {
				return desired;
			}
			return 0L;
		}

		public void reset() {
			recentPeakBytes = 0L;
			idleClearStreak = 0;
		}
	}

	public static void recordNativeReclaimed(long bytes) {
		if (bytes > 0L) {
			RECLAIMED_NATIVE_BYTES.addAndGet(bytes);
		}
	}

	public static void recordHeapReclaimed(long estimatedBytes) {
		if (estimatedBytes > 0L) {
			RECLAIMED_HEAP_ESTIMATE_BYTES.addAndGet(estimatedBytes);
		}
	}

	public static void recordDedupSaved(long count, long estimatedBytes) {
		if (count > 0L) {
			DEDUP_SAVED_OBJECTS.addAndGet(count);
		}
		if (estimatedBytes > 0L) {
			RECLAIMED_HEAP_ESTIMATE_BYTES.addAndGet(estimatedBytes);
		}
	}

	public static void recordWorldUnloadSweep() {
		WORLD_UNLOAD_SWEEPS.incrementAndGet();
	}

	public static void recordResourceReloadSweep() {
		RESOURCE_RELOAD_SWEEPS.incrementAndGet();
	}

	public static Snapshot snapshot() {
		return new Snapshot(
			RECLAIMED_NATIVE_BYTES.get(),
			RECLAIMED_HEAP_ESTIMATE_BYTES.get(),
			DEDUP_SAVED_OBJECTS.get(),
			WORLD_UNLOAD_SWEEPS.get(),
			RESOURCE_RELOAD_SWEEPS.get()
		);
	}

	public static void resetCountersForTests() {
		RECLAIMED_NATIVE_BYTES.set(0L);
		RECLAIMED_HEAP_ESTIMATE_BYTES.set(0L);
		DEDUP_SAVED_OBJECTS.set(0L);
		WORLD_UNLOAD_SWEEPS.set(0L);
		RESOURCE_RELOAD_SWEEPS.set(0L);
	}

	private static long nextPowerOfTwo(long v) {
		if (v <= 1L) {
			return 1L;
		}
		long highest = Long.highestOneBit(v - 1L);
		return highest >= (1L << 61) ? v : (highest << 1);
	}

	public record Snapshot(
		long reclaimedNativeBytes,
		long reclaimedHeapEstimateBytes,
		long dedupSavedObjects,
		long worldUnloadSweeps,
		long resourceReloadSweeps
	) {}
}
