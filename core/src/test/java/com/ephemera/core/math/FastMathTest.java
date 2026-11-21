package com.ephemera.core.math;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FastMathTest {
    @Test void saturatingPhaseConversionMatchesVanillaAtExtremeAngles() {
        for (double angle : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE, -Double.MAX_VALUE, 1e12, -1e12, 0.0, -0.0}) {
            double phase = angle * (65536.0 / (2 * Math.PI));
            assertEquals(referencePhase(phase), FastMath.sin(angle), 4e-7);
            assertEquals(referencePhase(phase + 16384.0), FastMath.cos(angle), 4e-7);
        }
    }

    private static double referencePhase(double phase) {
        return Math.sin(((long) phase & 65535) * (2 * Math.PI / 65536));
    }

    @Test void interpolatesEveryVanillaPhase() {
        for (int i = -65536; i < 65536; i++) {
            double angle = (i + 0.25) * (2 * Math.PI / 65536);
            assertEquals(Math.sin(angle), FastMath.sin(angle), 0.0001);
            assertEquals(Math.cos(angle), FastMath.cos(angle), 0.0001);
            int phase = (int) ((long) (angle * (65536 / (2 * Math.PI))) & 65535);
            assertEquals(Math.sin(phase * (2 * Math.PI / 65536)), FastMath.sin(angle), 4e-7);
        }
    }

    @Test void narrowingAndModuloMatchJavaAtBoundaries() {
        for (double value : new double[]{-3.9, -1, -0.0, 0.1, Integer.MIN_VALUE - 2d,
                Integer.MAX_VALUE + 2d, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            assertEquals((int) Math.floor(value), FastMath.floor(value));
            assertEquals((int) Math.ceil(value), FastMath.ceil(value));
        }
        for (int divisor : new int[]{Integer.MIN_VALUE, -16, -3, 1, 2, 3, 16, 1 << 30}) {
            for (int value : new int[]{Integer.MIN_VALUE, -17, -1, 0, 19, Integer.MAX_VALUE}) {
                assertEquals(Math.floorMod(value, divisor), FastMath.floorMod(value, divisor));
            }
        }
        assertThrows(ArithmeticException.class, () -> FastMath.floorMod(1, 0));
        for (int i = 0; i < 4096; i++) assertEquals(i, FastMath.sectionIndex(i & 15, i >> 8, i >> 4 & 15));
    }
}
