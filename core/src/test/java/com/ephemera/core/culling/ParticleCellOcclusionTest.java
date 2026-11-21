package com.ephemera.core.culling;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleCellOcclusionTest {
    private static boolean arena(int x, int y, int z) {
        return y == 129 || z == 10 && x >= -10 && x <= 10 && y >= 130 && y <= 136;
    }

    @Test void closeParticlesDoNotReadWorld() {
        ParticleCellOcclusion p = new ParticleCellOcclusion((x, y, z) -> { fail("close particle world read"); return true; });
        assertFalse(p.isOccluded(.5, 131.6, .5, .5, 131.6, 3.49, .2, 1, 1));
    }

    @Test void hiddenParticlesUseFewReadsThenAnEntireCellReusesTheProof() {
        int[] reads = {0};
        ParticleCellOcclusion p = new ParticleCellOcclusion((x, y, z) -> { reads[0]++; return arena(x, y, z); });
        assertTrue(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 1, 1));
        assertTrue(reads[0] <= 16, "wall next to target should take just one layer");
        int initial = reads[0];
        for (int i = 0; i < 200; i++) {
            double fraction = i / 200.0;
            assertTrue(p.isOccluded(.5, 131.6, .5, fraction, 130 + fraction, 12 + fraction,
                    fraction * .5, 1, 1));
        }
        assertEquals(initial, reads[0], "moving centers and radii in the cell must need zero further reads");
    }

    @Test void particlesAtDifferentWallHeightsAreHidden() {
        ParticleCellOcclusion p = new ParticleCellOcclusion(ParticleCellOcclusionTest::arena);
        for (double y : new double[] {130.5, 132.5, 134.5}) {
            assertTrue(p.isOccluded(.5, 131.6, .5, .5, y, 12.5, .2, 1, 1), "y=" + y);
        }
    }

    @Test void frontParticlesAndParticlesPeekingPastSideOrTopStayVisible() {
        ParticleCellOcclusion p = new ParticleCellOcclusion(ParticleCellOcclusionTest::arena);
        assertFalse(p.isOccluded(.5, 131.6, .5, .5, 132.5, 8.5, .2, 1, 1));
        // Quad reaches .1 past the edge; its center ray alone is blocked.
        assertFalse(p.isOccluded(11.1, 131.6, .5, 10.9, 132.5, 12.5, .2, 2, 1));
        assertFalse(p.isOccluded(.5, 137.2, .5, .5, 136.9, 12.5, .2, 3, 1));
    }

    @Test void frameTerrainAndCameraChangesInvalidateResults() {
        boolean[] wall = {true};
        int[] reads = {0};
        ParticleCellOcclusion p = new ParticleCellOcclusion((x, y, z) -> { reads[0]++; return wall[0] && arena(x, y, z); });
        assertTrue(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 1, 1));
        int afterFirst = reads[0];
        // Same terrainStamp and exact camera across frames reuses the cell proof with zero reads.
        assertTrue(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 2, 1));
        assertEquals(afterFirst, reads[0]);
        wall[0] = false;
        assertFalse(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 2, 2));
        wall[0] = true;
        assertTrue(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 3, 3));
        int before = reads[0];
        assertTrue(p.isOccluded(.51, 131.6, .5, .5, 130.5, 12.5, .2, 3, 3));
        assertTrue(reads[0] > before, "even movement within a quantized camera cell must invalidate");
        assertFalse(p.isOccluded(20.5, 131.6, .5, .5, 130.5, 12.5, .2, 3, 3));
    }

    @Test void collisionsOnlyEvictAndVisibleResultsAreCachedToo() {
        int[] reads = {0};
        ParticleCellOcclusion p = new ParticleCellOcclusion((x, y, z) -> { reads[0]++; return arena(x, y, z); });
        assertTrue(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 1, 1));
        assertFalse(p.isOccluded(.5, 131.6, .5, 4096.5, 130.5, 12.5, .2, 1, 1));
        int after = reads[0];
        assertFalse(p.isOccluded(.5, 131.6, .5, 4096.8, 130.8, 12.8, .5, 1, 1));
        assertEquals(after, reads[0]);
        assertTrue(p.isOccluded(.5, 131.6, .5, .5, 130.5, 12.5, .2, 1, 1));
        assertTrue(reads[0] > after);
    }

    @Test void reverseNegativeCoordinatesAndEveryAxisWork() {
        assertTrue(new ParticleCellOcclusion((x, y, z) -> x == -10).isOccluded(
                .5, .5, .5, -12.5, .5, .5, .2, 1, 1));
        assertTrue(new ParticleCellOcclusion((x, y, z) -> y == 10).isOccluded(
                .5, .5, .5, .5, 12.5, .5, .2, 1, 1));
        assertTrue(new ParticleCellOcclusion((x, y, z) -> z == -10).isOccluded(
                .5, .5, .5, .5, .5, -12.5, .2, 1, 1));
    }

    @Test void worldReadsAreBoundedAndUnknownAirFailsOpen() {
        int[] reads = {0};
        ParticleCellOcclusion p = new ParticleCellOcclusion((x, y, z) -> { reads[0]++; return false; });
        assertFalse(p.isOccluded(.5, .5, .5, .5, .5, 1000.5, .5, 1, 1));
        assertTrue(reads[0] > 0 && reads[0] <= 16, "an empty 1000-block ray still visits at most 16 cells");
        reads[0] = 0;
        ParticleCellOcclusion partial = new ParticleCellOcclusion((x, y, z) -> { reads[0]++; return x != 1 || y != 1; });
        partial.isOccluded(.5, .5, .5, .5, .5, 1000.5, .5, 1, 1);
        assertTrue(reads[0] <= 256);
    }

    @Test void unsupportedSizesAndInvalidInputsDoNotReadWorld() {
        ParticleCellOcclusion p = new ParticleCellOcclusion((x, y, z) -> { fail("invalid input world read"); return true; });
        for (double radius : new double[] {-.1, .5001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(p.isOccluded(.5, .5, .5, .5, .5, 12.5, radius, 1, 1));
        }
        assertFalse(p.isOccluded(.5, .5, .5, Double.NaN, .5, 12.5, .2, 1, 1));
        assertFalse(p.isOccluded(Double.NaN, .5, .5, .5, .5, 12.5, .2, 1, 1));
        assertFalse(p.isOccluded(.5, .5, .5, .5, 30_000_064, 12.5, .2, 1, 1));
    }

    @Test void randomizedOpenRaysThroughPunchedWallsNeverCull() {
        Random random = new Random(0x262E9FEL);
        for (int i = 0; i < 2000; i++) {
            double cx = random.nextDouble() * 8 - 4, cy = random.nextDouble() * 8 - 4;
            double x = random.nextDouble() * 8 - 4, y = random.nextDouble() * 8 - 4;
            double z = 12 + random.nextDouble() * 10, radius = random.nextDouble() * .5;
            // Punch a hole on an actual ray to a point INSIDE the rendered sphere.
            double ex = x + radius * .5, ey = y - radius * .5;
            double xa = cx + (ex - cx) * (10 / z), xb = cx + (ex - cx) * (11 / z);
            double ya = cy + (ey - cy) * (10 / z), yb = cy + (ey - cy) * (11 / z);
            int hx0 = (int) Math.floor(Math.min(xa, xb)), hx1 = (int) Math.floor(Math.max(xa, xb));
            int hy0 = (int) Math.floor(Math.min(ya, yb)), hy1 = (int) Math.floor(Math.max(ya, yb));
            ParticleCellOcclusion p = new ParticleCellOcclusion((bx, by, bz) -> bz == 10
                    && !(bx >= hx0 && bx <= hx1 && by >= hy0 && by <= hy1));
            assertFalse(p.isOccluded(cx, cy, 0, x, y, z, radius, 1, 1), "case " + i);
        }
    }
}
