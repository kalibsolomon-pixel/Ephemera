package com.ephemera.core.render;

import java.util.function.LongConsumer;

/** A bounded set of view handles belonging to one live native buffer. */
public final class BufferViewCache {
	private final long[] offsets;
	private final long[] ranges;
	private final int[] formats;
	private final long[] views;
	private int size;

	public BufferViewCache(int capacity) {
		offsets = new long[capacity];
		ranges = new long[capacity];
		formats = new int[capacity];
		views = new long[capacity];
	}

	public long find(long offset, long range, int format) {
		for (int i = 0; i < size; i++) {
			if (offsets[i] == offset && ranges[i] == range && formats[i] == format) {
				return views[i];
			}
		}
		return 0;
	}

	public boolean isFull() {
		return size == views.length;
	}

	/** The caller supplies a successful, nonzero handle for a missing key. */
	public void remember(long offset, long range, int format, long view) {
		offsets[size] = offset;
		ranges[size] = range;
		formats[size] = format;
		views[size++] = view;
	}

	public void destroyAll(LongConsumer destroy) {
		while (size > 0) {
			destroy.accept(views[--size]);
		}
	}
}
