package com.ephemera.core.culling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectMappedSweepCacheTest {
    private static int get(DirectMappedSweepCache c, long id, long stamp, long now) {
        return c.lookup(id, stamp, now, -1, 2, 3, 4, 5, 6, 7, 8, 9);
    }
    private static void put(DirectMappedSweepCache c, long id, long stamp, long now, boolean hidden) {
        c.put(id, stamp, now, -1, 2, 3, 4, 5, 6, 7, 8, 9, hidden);
    }
    @Test void bothResultsRoundTrip() {
        DirectMappedSweepCache c = new DirectMappedSweepCache(1024);
        assertEquals(-1, get(c, 0, 0, 0));
        put(c, 17, 4, 10, true); assertEquals(1, get(c, 17, 4, 11));
        put(c, 17, 4, 20, false); assertEquals(0, get(c, 17, 4, 21));
    }
    @Test void ttlDoesNotSlideOnRead() {
        DirectMappedSweepCache c = new DirectMappedSweepCache(512);
        put(c, 1, 2, 50, true);
        assertEquals(1, get(c, 1, 2, 50 + DirectMappedSweepCache.TTL_NANOS - 1));
        assertEquals(-1, get(c, 1, 2, 50 + DirectMappedSweepCache.TTL_NANOS));
        assertEquals(-1, get(c, 1, 2, 49));
    }
    @Test void terrainAndIdArePartOfExactKey() {
        DirectMappedSweepCache c = new DirectMappedSweepCache(1);
        put(c, 10, 2, 0, true);
        assertEquals(-1, get(c, 10, 3, 1)); assertEquals(-1, get(c, 11, 2, 1));
        put(c, 11, 2, 2, false); assertEquals(-1, get(c, 10, 2, 3));
    }
    @Test void allNineQuantizedCoordinatesInvalidateIndependently() {
        DirectMappedSweepCache c = new DirectMappedSweepCache(1);
        int[] coordinates = {-1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int i = 0; i < 9; i++) {
            put(c, 1, 2, 0, true);
            coordinates[i]++;
            assertEquals(-1, c.lookup(1, 2, 1, coordinates[0], coordinates[1], coordinates[2],
                    coordinates[3], coordinates[4], coordinates[5], coordinates[6], coordinates[7], coordinates[8]));
            coordinates[i]--;
        }
    }
    @Test void clearAndSlotValidation() {
        DirectMappedSweepCache c = new DirectMappedSweepCache(1);
        put(c, 1, 2, 0, true); c.clear(); assertEquals(-1, get(c, 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new DirectMappedSweepCache(3));
        assertThrows(IllegalArgumentException.class, () -> new DirectMappedSweepCache(0));
    }
    @Test void packedBlockPosGridHasMinimalCollisions() {
        DirectMappedSweepCache c = new DirectMappedSweepCache(DirectMappedSweepCache.BLOCK_ENTITY_SLOTS);
        long[] ids = new long[64];
        int k = 0;
        for (int z : new int[] {6, 14}) {
            for (int y : new int[] {130, 131}) {
                for (int x = -8; x <= 7; x++) {
                    // Matches Minecraft BlockPos.asLong bit layout: (x<<38) | (z<<12) | y.
                    long id = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
                    ids[k++] = id;
                    put(c, id, 1, 100, z == 14);
                }
            }
        }
        int hits = 0;
        for (int i = 0; i < 64; i++) {
            if (get(c, ids[i], 1, 101) != DirectMappedSweepCache.MISS) hits++;
        }
        assertTrue(hits >= 60, "expected >=60/64 block-entity hits without slot thrashing, got " + hits);
    }
}
