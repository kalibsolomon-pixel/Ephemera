package com.ephemera.core.lighting;

import java.util.NoSuchElementException;

/** Single-owner FIFO of packed light updates. All position and metadata bits are retained. */
public final class FastLightQueue {
    private long[] entries = new long[32];
    private int head;
    private int size;

    public void add(long entry) {
        if (size == entries.length) {
            long[] expanded = new long[Math.multiplyExact(size, 2)];
            int first = size - head;
            System.arraycopy(entries, head, expanded, 0, first);
            System.arraycopy(entries, 0, expanded, first, head);
            entries = expanded;
            head = 0;
        }
        entries[(head + size) & (entries.length - 1)] = entry;
        size++;
    }

    public long remove() {
        if (size == 0) throw new NoSuchElementException();
        long value = entries[head];
        head = (head + 1) & (entries.length - 1);
        size--;
        // Shrink progressively once utilization drops below 1/4 instead of retaining megabytes
        // of ring after a relight storm until a fully-drained instant that may never come. The
        // halving floor of 4096 stays above LightWorkMixin's 2048-per-call burst constant so
        // routine chunk-load waves never oscillate the capacity; every length stays a power of
        // two for the index masks.
        int cap = entries.length;
        if (cap > 4096 && size < (cap >> 2)) {
            compact(Math.max(4096, cap >> 1));
        }
        return value;
    }

    private void compact(int newCap) {
        long[] shrunk = new long[newCap];
        int wrapped = Math.min(size, entries.length - head);
        System.arraycopy(entries, head, shrunk, 0, wrapped);
        System.arraycopy(entries, 0, shrunk, wrapped, size - wrapped);
        entries = shrunk;
        head = 0;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The 4096 local positions consume twelve bits, the light level four. */
    public static int packLocal(int x, int y, int z, int level) {
        return ((level & 15) << 12) | ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }
}
