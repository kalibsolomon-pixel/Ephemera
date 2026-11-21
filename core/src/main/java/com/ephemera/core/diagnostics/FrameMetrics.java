package com.ephemera.core.diagnostics;

import java.util.Arrays;

/**
 * Zero-allocation ring buffer for accurate frame-time distribution statistics:
 * average FPS, mean/median/p95/p99 frame times, and 1% / 0.1% low FPS.
 */
public final class FrameMetrics {
	private final long[] frameNanos;
	private final long[] scratch;
	private int cursor;
	private int count;
	private long lastFrameNs;

	public FrameMetrics(int capacity) {
		this.frameNanos = new long[capacity];
		this.scratch = new long[capacity];
	}

	public void reset() {
		cursor = 0;
		count = 0;
		lastFrameNs = 0L;
	}

	public void recordTimestamp(long nowNs) {
		if (lastFrameNs != 0L) {
			long dt = nowNs - lastFrameNs;
			if (dt > 0L) {
				frameNanos[cursor] = dt;
				cursor = (cursor + 1) % frameNanos.length;
				if (count < frameNanos.length) {
					count++;
				}
			}
		}
		lastFrameNs = nowNs;
	}

	public int sampleCount() {
		return count;
	}

	public Snapshot snapshot() {
		if (count == 0) {
			return Snapshot.EMPTY;
		}
		int n = count;
		System.arraycopy(frameNanos, 0, scratch, 0, n);
		Arrays.sort(scratch, 0, n);

		long totalNs = 0L;
		for (int i = 0; i < n; i++) {
			totalNs += scratch[i];
		}

		double meanMs = (totalNs / (double) n) / 1_000_000.0;
		double avgFps = totalNs > 0L ? (n * 1_000_000_000.0) / totalNs : 0.0;
		double medianMs = percentile(scratch, n, 0.50) / 1_000_000.0;
		double p95Ms = percentile(scratch, n, 0.95) / 1_000_000.0;
		double p99Ms = percentile(scratch, n, 0.99) / 1_000_000.0;
		double p999Ms = percentile(scratch, n, 0.999) / 1_000_000.0;

		double onePercentLowFps = tailMeanFps(scratch, n, 0.01);
		double pointOnePercentLowFps = tailMeanFps(scratch, n, 0.001);

		return new Snapshot(
			n,
			avgFps,
			meanMs,
			medianMs,
			p95Ms,
			p99Ms,
			p999Ms,
			onePercentLowFps,
			pointOnePercentLowFps
		);
	}

	private static long percentile(long[] sorted, int n, double p) {
		int idx = Math.min(n - 1, Math.max(0, (int) Math.ceil(p * n) - 1));
		return sorted[idx];
	}

	private static double tailMeanFps(long[] sorted, int n, double tailFraction) {
		int tailCount = Math.max(1, (int) Math.ceil(n * tailFraction));
		long sumNs = 0L;
		for (int i = n - tailCount; i < n; i++) {
			sumNs += sorted[i];
		}
		return sumNs > 0L ? (tailCount * 1_000_000_000.0) / sumNs : 0.0;
	}

	public record Snapshot(
		int samples,
		double avgFps,
		double meanMs,
		double medianMs,
		double p95Ms,
		double p99Ms,
		double p999Ms,
		double onePercentLowFps,
		double pointOnePercentLowFps
	) {
		public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
}
