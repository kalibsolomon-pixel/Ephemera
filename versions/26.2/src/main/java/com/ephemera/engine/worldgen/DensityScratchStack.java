package com.ephemera.engine.worldgen;

/**
 * Depth-indexed ThreadLocal {@code double[]} scratch stack for {@code DensityFunctions$Ap2.fillArray}.
 *
 * <p>Vanilla allocates {@code new double[array.length]} on every {@code ADD} density node per chunk
 * cell (~1.49 GB / 45s on {@code Worker-Main}). Reusing depth-indexed scratch arrays handles
 * arbitrary recursive nesting of {@code Ap2} nodes with zero heap allocation.
 */
public final class DensityScratchStack {
	private static final int MAX_DEPTH = 24;
	private static final int MAX_POOLED_LEN = 4096;

	private static final class Stack {
		final double[][] arrays = new double[MAX_DEPTH][];
		int depth;
	}

	private static final ThreadLocal<Stack> STACK = ThreadLocal.withInitial(Stack::new);

	private DensityScratchStack() {}

	public static double[] borrow(int len) {
		Stack s = STACK.get();
		int d = s.depth++;
		if (d < MAX_DEPTH && len <= MAX_POOLED_LEN) {
			double[] arr = s.arrays[d];
			if (arr == null || arr.length != len) {
				arr = new double[len];
				s.arrays[d] = arr;
			}
			return arr;
		}
		return new double[len];
	}

	public static void release() {
		Stack s = STACK.get();
		if (s.depth > 0) {
			s.depth--;
		}
	}
}
