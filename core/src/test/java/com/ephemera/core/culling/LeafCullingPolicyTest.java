package com.ephemera.core.culling;

import org.junit.jupiter.api.Test;
import static com.ephemera.core.culling.LeafCullingPolicy.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class LeafCullingPolicyTest {
    @Test void offAlwaysPreservesFaces() { assertFalse(LeafCullingPolicy.skip(OFF, true, true, 63, 8, 1)); }
    @Test void fastModeRequiresOpaqueLeavesAndLeafNeighbor() {
        assertTrue(LeafCullingPolicy.skip(FAST_OPAQUE, true, true, 0, 0, 1));
        assertFalse(LeafCullingPolicy.skip(FAST_OPAQUE, false, true, 63, 8, 1));
        assertFalse(LeafCullingPolicy.skip(FAST_OPAQUE, true, false, 63, 8, 1));
    }
    @Test void surroundingNeedsEveryFace() {
        assertTrue(LeafCullingPolicy.skip(CHECK_SURROUNDING, false, false, 63, 0, 1));
        for (int bit = 0; bit < 6; bit++) assertFalse(LeafCullingPolicy.skip(CHECK_SURROUNDING, true, true, 63 ^ (1 << bit), 8, 1));
    }
    @Test void depthNeedsOpaqueProofAndClampsConfiguration() {
        assertFalse(LeafCullingPolicy.skip(DEPTH, false, true, 0, 100, 2));
        assertFalse(LeafCullingPolicy.skip(DEPTH, false, false, 63, 1, 2));
        assertTrue(LeafCullingPolicy.skip(DEPTH, false, false, 63, 2, 2));
        assertTrue(LeafCullingPolicy.skip(DEPTH, false, false, 63, 1, -100));
        assertTrue(LeafCullingPolicy.skip(DEPTH, false, false, 63, 8, 100));
    }
}
