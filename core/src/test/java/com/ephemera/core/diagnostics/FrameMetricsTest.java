package com.ephemera.core.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameMetricsTest {
	@Test
	void computesAccuratePercentilesAndLowFps() {
		FrameMetrics m = new FrameMetrics(1000);
		long t = 1_000_000_000L;
		m.recordTimestamp(t);
		// 99 frames at 1.0 ms, 1 spike at 10.0 ms
		for (int i = 0; i < 99; i++) {
			t += 1_000_000L;
			m.recordTimestamp(t);
		}
		t += 10_000_000L;
		m.recordTimestamp(t);

		FrameMetrics.Snapshot s = m.snapshot();
		assertEquals(100, s.samples());
		assertEquals(1.0, s.medianMs(), 0.001);
		assertEquals(1.0, s.p95Ms(), 0.001);
		assertEquals(1.0, s.p99Ms(), 0.001);
		assertEquals(10.0, s.p999Ms(), 0.001);
		assertEquals(100.0, s.onePercentLowFps(), 0.5);
		assertEquals(1.09, s.meanMs(), 0.001);
	}
}
