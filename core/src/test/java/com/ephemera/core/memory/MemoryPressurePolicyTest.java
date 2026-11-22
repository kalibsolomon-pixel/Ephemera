package com.ephemera.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MemoryPressurePolicyTest {
	@BeforeEach
	void reset() {
		MemoryPressurePolicy.resetCountersForTests();
	}

	@Test
	void shrinksNativeBufferOnlyAfterSustainedLowUtilization() {
		var tracker = new MemoryPressurePolicy.NativeBufferShrinkTracker(
			256L * 1024L,
			4L * 1024L * 1024L,
			20
		);
		long bloated = 16L * 1024L * 1024L;
		for (int i = 0; i < 19; i++) {
			assertEquals(0L, tracker.onClear(bloated, 128L * 1024L), "Must not shrink before idle streak completes");
		}
		long target = tracker.onClear(bloated, 128L * 1024L);
		assertEquals(256L * 1024L, target);
	}

	@Test
	void highUsageResetsShrinkStreakAndPreventsThrashing() {
		var tracker = new MemoryPressurePolicy.NativeBufferShrinkTracker(
			256L * 1024L,
			4L * 1024L * 1024L,
			20
		);
		long bloated = 16L * 1024L * 1024L;
		for (int i = 0; i < 15; i++) {
			assertEquals(0L, tracker.onClear(bloated, 100_000L));
		}
		// 40% usage proves the larger buffer is still needed.
		assertEquals(0L, tracker.onClear(bloated, 7L * 1024L * 1024L));
		for (int i = 0; i < 19; i++) {
			assertEquals(0L, tracker.onClear(bloated, 100_000L));
		}
		long shrunk = tracker.onClear(bloated, 100_000L);
		assertTrue(shrunk > 0L && shrunk < bloated);
	}

	@Test
	void telemetryAccumulatesNativeHeapDedupAndSweeps() {
		MemoryPressurePolicy.recordNativeReclaimed(4_000_000L);
		MemoryPressurePolicy.recordHeapReclaimed(2_000_000L);
		MemoryPressurePolicy.recordDedupSaved(100L, 4_800L);
		MemoryPressurePolicy.recordWorldUnloadSweep();
		MemoryPressurePolicy.recordResourceReloadSweep();

		var snap = MemoryPressurePolicy.snapshot();
		assertEquals(4_000_000L, snap.reclaimedNativeBytes());
		assertEquals(2_004_800L, snap.reclaimedHeapEstimateBytes());
		assertEquals(100L, snap.dedupSavedObjects());
		assertEquals(1L, snap.worldUnloadSweeps());
		assertEquals(1L, snap.resourceReloadSweeps());
	}
}
