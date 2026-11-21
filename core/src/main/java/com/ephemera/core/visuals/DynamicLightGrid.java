package com.ephemera.core.visuals;

import java.util.Arrays;

/**
 * Allocation-free spatial hash of dynamic light emitters. Emitters are bucketed into
 * {@value #CELL_SIZE}-block cells; a query visits only the cells its maximum reach touches.
 * Light levels are vanilla-scale (0..15) floats so callers can feed smooth-lighting coords.
 */
public final class DynamicLightGrid {
	public static final int MAX_LEVEL = 15;
	private static final int CELL_SIZE = 16;
	private static final int CELL_SHIFT = 4;

	private final int capacity;
	private final double[] xs;
	private final double[] ys;
	private final double[] zs;
	private final int[] levels;
	private final int[] next;
	private int count;

	private final long[] cellKeys;
	private final int[] cellHeads;
	private final int tableMask;

	public DynamicLightGrid(int capacity) {
		this.capacity = Math.max(1, capacity);
		this.xs = new double[this.capacity];
		this.ys = new double[this.capacity];
		this.zs = new double[this.capacity];
		this.levels = new int[this.capacity];
		this.next = new int[this.capacity];
		int table = Integer.highestOneBit(Math.max(4, this.capacity * 2 - 1)) << 1;
		this.cellKeys = new long[table];
		this.cellHeads = new int[table];
		this.tableMask = table - 1;
		clear();
	}

	public void clear() {
		count = 0;
		Arrays.fill(cellHeads, -1);
	}

	public int size() {
		return count;
	}

	public boolean isEmpty() {
		return count == 0;
	}

	/** Adds an emitter. Levels outside 1..15 are clamped or ignored; a full grid drops the emitter. */
	public boolean add(double x, double y, double z, int level) {
		if (level <= 0 || count >= capacity) {
			return false;
		}
		int idx = count++;
		xs[idx] = x;
		ys[idx] = y;
		zs[idx] = z;
		levels[idx] = Math.min(level, MAX_LEVEL);

		long key = cellKey(cell(x), cell(y), cell(z));
		int slot = findSlot(key, true);
		next[idx] = cellHeads[slot];
		cellHeads[slot] = idx;
		return true;
	}

	/** Strongest dynamic light level (0..15, fractional) reaching the point, or 0 if none. */
	public float lightAt(double x, double y, double z) {
		if (count == 0) {
			return 0.0f;
		}
		int minCx = cell(x - MAX_LEVEL);
		int maxCx = cell(x + MAX_LEVEL);
		int minCy = cell(y - MAX_LEVEL);
		int maxCy = cell(y + MAX_LEVEL);
		int minCz = cell(z - MAX_LEVEL);
		int maxCz = cell(z + MAX_LEVEL);

		float best = 0.0f;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cy = minCy; cy <= maxCy; cy++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					int slot = findSlot(cellKey(cx, cy, cz), false);
					if (slot < 0) {
						continue;
					}
					for (int e = cellHeads[slot]; e >= 0; e = next[e]) {
						double dx = xs[e] - x;
						double dy = ys[e] - y;
						double dz = zs[e] - z;
						float v = falloff(dx * dx + dy * dy + dz * dz, levels[e]);
						if (v > best) {
							best = v;
						}
					}
				}
			}
		}
		return best;
	}

	/**
	 * Smooth quadratic falloff: an emitter of level L reaches L blocks and contributes
	 * {@code L * (1 - d / L)^2}, so it peaks at L on the emitter and fades with zero slope at the edge.
	 */
	public static float falloff(double distanceSq, int level) {
		if (level <= 0) {
			return 0.0f;
		}
		double reachSq = (double) level * level;
		if (distanceSq >= reachSq) {
			return 0.0f;
		}
		double t = 1.0 - Math.sqrt(distanceSq) / level;
		return (float) (level * t * t);
	}

	private int findSlot(long key, boolean insert) {
		int slot = mix(key) & tableMask;
		while (true) {
			if (cellHeads[slot] < 0) {
				if (!insert) {
					return -1;
				}
				cellKeys[slot] = key;
				return slot;
			}
			if (cellKeys[slot] == key) {
				return slot;
			}
			slot = (slot + 1) & tableMask;
		}
	}

	private static int cell(double v) {
		return (int) Math.floor(v) >> CELL_SHIFT;
	}

	private static long cellKey(int cx, int cy, int cz) {
		return ((long) cx & 0x1FFFFFL) | (((long) cz & 0x1FFFFFL) << 21) | (((long) cy & 0x3FFFFL) << 42);
	}

	private static int mix(long key) {
		key ^= key >>> 33;
		key *= 0xff51afd7ed558ccdL;
		key ^= key >>> 33;
		return (int) key;
	}
}
