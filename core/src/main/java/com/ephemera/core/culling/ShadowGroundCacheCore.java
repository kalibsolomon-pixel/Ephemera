package com.ephemera.core.culling;

import java.util.Arrays;

/** Primitive block-coordinate cache. Values describe terrain only, never entity alpha or light. */
public final class ShadowGroundCacheCore {
    public static final long MISS = Long.MIN_VALUE;
    private final int[] xs, ys, zs;
    private final long[] stamps, surfaces;
    private final int mask;
    public ShadowGroundCacheCore(int slots) {
        if (slots < 1 || Integer.bitCount(slots) != 1) throw new IllegalArgumentException("power of two required");
        xs = new int[slots]; ys = new int[slots]; zs = new int[slots];
        stamps = new long[slots]; surfaces = new long[slots]; mask = slots - 1;
        clear();
    }
    private int slot(int x, int y, int z) { return (x * 73428767 ^ y * 912931 ^ z * 438289) & mask; }
    public long get(int x, int y, int z, long stamp) {
        int s = slot(x, y, z);
        return xs[s] == x && ys[s] == y && zs[s] == z && stamps[s] == stamp ? surfaces[s] : MISS;
    }
    public void put(int x, int y, int z, long stamp, long packedShadowSurface) {
        if (packedShadowSurface == MISS) throw new IllegalArgumentException("reserved miss value");
        int s = slot(x, y, z);
        xs[s] = x; ys[s] = y; zs[s] = z; stamps[s] = stamp; surfaces[s] = packedShadowSurface;
    }
    public void clear() { Arrays.fill(surfaces, MISS); }
}
