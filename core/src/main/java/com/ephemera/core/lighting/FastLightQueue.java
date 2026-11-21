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
        // Retain ordinary bursts, release exceptionally large buffers after the wave.
        if (size == 0 && entries.length > 16384) {
            entries = new long[1024];
            head = 0;
        }
        return value;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The 4096 local positions consume twelve bits, the light level four. */
    public static int packLocal(int x, int y, int z, int level) {
        return ((level & 15) << 12) | ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }
}
