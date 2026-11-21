package com.ephemera.core.culling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CullingBoundsTest {
    private static final OpaqueLayerSweep.Solidity ARENA = (x, y, z) ->
            y == 129 || z == 10 && x >= -10 && x <= 10 && y >= 130 && y <= 136;

    private static boolean hidden(CullingBounds b, double cameraX, double cameraY) {
        // Same outward quarter-block quantization and expanded camera as the client cache.
        double cx = Math.floor(cameraX * 4) * .25, cy = Math.floor(cameraY * 4) * .25;
        return new OpaqueLayerSweep(ARENA).isOccluded(cx - .125, cy - .125, .375,
                cx + .375, cy + .375, .875,
                Math.floor(b.x0 * 4) * .25, Math.floor(b.y0 * 4) * .25, Math.floor(b.z0 * 4) * .25,
                Math.ceil(b.x1 * 4) * .25, Math.ceil(b.y1 * 4) * .25, Math.ceil(b.z1 * 4) * .25);
    }

    private static CullingBounds pig(double x, double y, double z) {
        CullingBounds b = new CullingBounds();
        b.entity(x - .45, y, z - .45, x + .45, y + .9, z + .45, 0, 0, 0, 1);
        b.shadow(x, y, z, x, y, z, .5);
        return b;
    }

    @Test void pigOnSingleBlockFloorBehindWallIsHidden() {
        assertTrue(hidden(pig(.5, 130, 12.5), .5, 131.6));
        assertTrue(hidden(pig(.5, 130, 14.3), .5, 131.6));
    }

    @Test void chestOnSingleBlockFloorBehindWallIsHidden() {
        CullingBounds b = new CullingBounds();
        b.block(0, 130, 12);
        assertTrue(hidden(b, .5, 131.6));
    }

    @Test void actualEdgeSightLinesRemainVisible() {
        // The camera must also see past the edge: x=10.5 behind a wall ending at x=11
        // is still hidden from x=.5. Aligning the camera exposes a true .1-block slit.
        CullingBounds b = pig(11.1, 130, 12.5);
        assertFalse(hidden(b, 11.1, 131.6));
        b.block(11, 130, 12);
        assertFalse(hidden(b, 11.1, 131.6));
        assertFalse(hidden(pig(.5, 136.5, 12.5), .5, 138));
        b.block(0, 137, 12);
        assertFalse(hidden(b, .5, 138));
    }

    @Test void interpolationAndAsymmetricPaddingCoverTheEntireTick() {
        CullingBounds b = new CullingBounds();
        b.entity(0, 130, 12, .9, 130.9, 12.9, -1.2, .4, .7, 1);
        assertEquals(-1.5, b.x0, 1e-10);
        assertEquals(129.9375, b.y0, 1e-10);
        assertEquals(11.7, b.z0, 1e-10);
        assertEquals(1.2, b.x1, 1e-10);
        assertEquals(131.55, b.y1, 1e-10);
        assertEquals(13.9, b.z1, 1e-10);
    }

    @Test void shadowBoundsCoverCompleteSampledCellsAndAirborneDepth() {
        CullingBounds b = new CullingBounds();
        b.entity(.05, 130, 12.05, .95, 130.9, 12.95, 0, 0, 0, 1);
        b.shadow(.5, 130, 12.5, .5, 130, 12.5, .5);
        assertEquals(129, b.y0);
        assertEquals(-.25, b.x0); // Model padding extends farther than the first shadow cell.
        assertEquals(2, b.x1); // Vanilla samples both blocks when x+radius is integral.
        assertEquals(14, b.z1);
        b.entity(1.65, 134.1, 12.75, 2.55, 135, 13.65, -1.6, -.2, -.7, 1);
        b.shadow(.5, 133.9, 12.5, 2.1, 134.1, 13.2, 2);
        assertEquals(-2, b.x0);
        assertEquals(5, b.x1);
        assertEquals(131, b.y0);
        assertTrue(b.y1 >= 135);
        assertEquals(10, b.z0);
        assertEquals(16, b.z1);
    }
}
