package com.ephemera.core.culling;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class OpaqueLayerSweepTest {
    private static boolean hidden(OpaqueLayerSweep.Solidity blocks) {
        return new OpaqueLayerSweep(blocks).isOccluded(-.25, 1.25, -.25, .25, 1.75, .25,
                8, 0, -1, 9, 3, 1);
    }
    @Test void completeWallOccludesEveryRay() { assertTrue(hidden((x, y, z) -> x == 4)); }
    @Test void openAirIsVisible() { assertFalse(hidden((x, y, z) -> false)); }
    @Test void openSightLineThroughWallIsVisible() {
        assertFalse(hidden((x, y, z) -> x == 4 && !(y == 1 && z == 0)));
    }
    @Test void aFewBlockedSampleRaysDoNotProveOcclusion() {
        assertFalse(hidden((x, y, z) -> x == 4 && (y == 0 || y == 2)));
    }
    @Test void targetPartiallyAboveWallRemainsVisible() {
        assertFalse(hidden((x, y, z) -> x == 4 && y <= 1));
    }
    @Test void axisGapRejectsOverlappingCameraWithoutReadingWorld() {
        OpaqueLayerSweep sweep = new OpaqueLayerSweep((x, y, z) -> { fail("No separating layer"); return true; });
        assertFalse(sweep.isOccluded(0, 0, 0, 1, 1, 1, .5, .5, .5, 1.5, 1.5, 1.5));
        assertFalse(sweep.isOccluded(0, 0, 0, .25, .25, .25, .5, 0, 0, .75, .25, .25));
    }
    @Test void negativeCoordinatesAndReverseAxisWork() {
        assertTrue(new OpaqueLayerSweep((x, y, z) -> x == -5).isOccluded(0, 1, 0, .25, 1.5, .25,
                -10, 0, -1, -9, 3, 1));
    }
    @Test void allThreeAxisPermutationsWork() {
        assertTrue(new OpaqueLayerSweep((x, y, z) -> y == 4).isOccluded(0, 0, 0, .25, .25, .25,
                -1, 8, -1, 1, 9, 1));
        assertTrue(new OpaqueLayerSweep((x, y, z) -> z == 4).isOccluded(0, 0, 0, .25, .25, .25,
                -1, -1, 8, 1, 1, 9));
    }
    @Test void finiteBudgetFailsOpen() {
        assertFalse(new OpaqueLayerSweep((x, y, z) -> true, 1).isOccluded(0, 0, 0, 1, 1, 1,
                8, -10, -10, 9, 10, 10));
    }
    @Test void invalidBoundsFailOpen() {
        OpaqueLayerSweep sweep = new OpaqueLayerSweep((x, y, z) -> true);
        assertFalse(sweep.isOccluded(0, 0, 0, 1, 1, 1, Double.NaN, 0, 0, 9, 1, 1));
        assertFalse(sweep.isOccluded(0, 0, 0, 1, 1, 1, 10, 0, 0, 9, 1, 1));
        assertFalse(sweep.isOccluded(0, 0, 0, 1, 1, 1, 8, 0, 0, Double.POSITIVE_INFINITY, 1, 1));
    }
    @Test void cameraCubeExpansionCanRevealWallEdge() {
        OpaqueLayerSweep.Solidity wall = (x, y, z) -> x == 4 && z == 0;
        OpaqueLayerSweep sweep = new OpaqueLayerSweep(wall);
        assertTrue(sweep.isOccluded(0, 1.1, .4, .1, 1.2, .6, 8, 1.1, .4, 9, 1.2, .6));
        assertFalse(sweep.isOccluded(0, 1.1, -2, .1, 1.2, 3, 8, 1.1, .4, 9, 1.2, .6));
    }
    @Test void randomizedOpenRaysNeverProduceAnOcclusionProof() {
        // Deliberately punch a hole at the intersection of an actual camera-to-target ray.
        Random random = new Random(0xE9FE);
        for (int i = 0; i < 1000; i++) {
            double cy = random.nextDouble() * 4 - 2, cz = random.nextDouble() * 4 - 2;
            double ty = random.nextDouble() * 6 - 3, tz = random.nextDouble() * 6 - 3;
            int holeY = (int) Math.floor(cy + (ty - cy) * .5);
            int holeZ = (int) Math.floor(cz + (tz - cz) * .5);
            OpaqueLayerSweep sweep = new OpaqueLayerSweep((x, y, z) -> x == 4 && !(y == holeY && z == holeZ));
            assertFalse(sweep.isOccluded(.5, cy - .1, cz - .1, .6, cy + .1, cz + .1,
                    8.5, ty - .1, tz - .1, 8.6, ty + .1, tz + .1), "seed case " + i);
        }
    }

    @Test void thinWallProofNeedsOnlyASmallNumberOfVoxelReads() {
        int[] reads = {0};
        OpaqueLayerSweep sweep = new OpaqueLayerSweep((x, y, z) -> {
            reads[0]++;
            return y == 129 || z == 10 && x >= -10 && x <= 10 && y >= 130 && y <= 136;
        });
        assertTrue(sweep.isOccluded(.375, 131.375, .375, .875, 131.875, .875,
                -.25, 129, 11.75, 1.25, 131.25, 13.25));
        assertTrue(reads[0] <= 32, "a nearby full separating layer should not exhaust the 512-read budget");
        assertEquals(reads[0], sweep.lastTestsUsed());
    }

    @Test void centerVoxelEarlyOutRejectsOpenAirInOneProbePerLayer() {
        int[] reads = {0};
        OpaqueLayerSweep sweep = new OpaqueLayerSweep((x, y, z) -> { reads[0]++; return false; });
        assertFalse(sweep.isOccluded(0, 1, 0, .5, 1.5, .5, 20, 0, -2, 22, 3, 2));
        // Only X axis has a separating gap of 19 layers -> 1 center-voxel read per layer = 19 reads
        assertTrue(reads[0] <= 20, "open-air early-out should check 1 center voxel per layer, got " + reads[0]);
        assertEquals(reads[0], sweep.lastTestsUsed());
    }
}
