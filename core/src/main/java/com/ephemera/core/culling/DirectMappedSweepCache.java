package com.ephemera.core.culling;

import java.util.Arrays;

/** Exact primitive keys; hash collisions can only evict a result, never reuse another key. */
public final class DirectMappedSweepCache {
    public static final int ENTITY_SLOTS = 1024, BLOCK_ENTITY_SLOTS = 512;
    public static final long TTL_NANOS = 250_000_000L;
    public static final int MISS = -1, VISIBLE = 0, OCCLUDED = 1;
    private final long[] ids, stamps, times;
    private final int[][] coordinates;
    private final byte[] results;
    private final int mask;

    public DirectMappedSweepCache(int slots) {
        if (slots < 1 || Integer.bitCount(slots) != 1) throw new IllegalArgumentException("power of two required");
        mask = slots - 1;
        ids = new long[slots]; stamps = new long[slots]; times = new long[slots];
        coordinates = new int[9][slots]; results = new byte[slots];
        clear();
    }
    public void clear() { Arrays.fill(results, (byte) MISS); }
    private int slot(long id) { return (int) (id ^ (id >>> 32) ^ (id >>> 17)) & mask; }
    public int lookup(long id, long stamp, long now, int cx, int cy, int cz,
                      int x0, int y0, int z0, int x1, int y1, int z1) {
        int s = slot(id);
        long age = now - times[s];
        if (results[s] == MISS || ids[s] != id || stamps[s] != stamp || age < 0 || age >= TTL_NANOS
                || coordinates[0][s] != cx || coordinates[1][s] != cy || coordinates[2][s] != cz
                || coordinates[3][s] != x0 || coordinates[4][s] != y0 || coordinates[5][s] != z0
                || coordinates[6][s] != x1 || coordinates[7][s] != y1 || coordinates[8][s] != z1) return MISS;
        return results[s];
    }
    public void put(long id, long stamp, long now, int cx, int cy, int cz,
                    int x0, int y0, int z0, int x1, int y1, int z1, boolean occluded) {
        int s = slot(id);
        ids[s] = id; stamps[s] = stamp; times[s] = now;
        coordinates[0][s] = cx; coordinates[1][s] = cy; coordinates[2][s] = cz;
        coordinates[3][s] = x0; coordinates[4][s] = y0; coordinates[5][s] = z0;
        coordinates[6][s] = x1; coordinates[7][s] = y1; coordinates[8][s] = z1;
        results[s] = (byte) (occluded ? OCCLUDED : VISIBLE);
    }
}
