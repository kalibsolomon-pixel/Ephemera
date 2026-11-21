package com.ephemera.core.culling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowGroundCacheCoreTest {
    @Test void fullPrimitivePayloadAndNegativeCoordinatesRoundTrip() {
        ShadowGroundCacheCore c = new ShadowGroundCacheCore(4096);
        assertEquals(ShadowGroundCacheCore.MISS, c.get(0, 0, 0, 0));
        c.put(-30_000_000, -64, 29_999_999, 42, 0x7fff_ffff_1234_5678L);
        assertEquals(0x7fff_ffff_1234_5678L, c.get(-30_000_000, -64, 29_999_999, 42));
    }
    @Test void eachCoordinateAndRevisionIsCheckedAfterHashCollision() {
        ShadowGroundCacheCore c = new ShadowGroundCacheCore(1);
        c.put(1, 2, 3, 4, 8);
        assertEquals(ShadowGroundCacheCore.MISS, c.get(2, 2, 3, 4));
        assertEquals(ShadowGroundCacheCore.MISS, c.get(1, 3, 3, 4));
        assertEquals(ShadowGroundCacheCore.MISS, c.get(1, 2, 4, 4));
        assertEquals(ShadowGroundCacheCore.MISS, c.get(1, 2, 3, 5));
        c.put(2, 2, 3, 4, 9); assertEquals(ShadowGroundCacheCore.MISS, c.get(1, 2, 3, 4));
    }
    @Test void clearAndReservedPayloadAreSafe() {
        ShadowGroundCacheCore c = new ShadowGroundCacheCore(1);
        c.put(0, 0, 0, 0, 0); assertEquals(0, c.get(0, 0, 0, 0));
        c.clear(); assertEquals(ShadowGroundCacheCore.MISS, c.get(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> c.put(0, 0, 0, 0, ShadowGroundCacheCore.MISS));
        assertThrows(IllegalArgumentException.class, () -> new ShadowGroundCacheCore(5));
    }
}
