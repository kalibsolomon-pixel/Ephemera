package com.ephemera.core.math;

/** Compact sine table indexed using the game's 16-bit phase convention. */
public final class FastMath {
    private static final float[] SINE = new float[4096];
    private static final double PHASE_SCALE = 65536.0 / (2.0 * Math.PI);

    static {
        for (int i = 0; i < SINE.length; i++) {
            SINE[i] = (float) StrictMath.sin(i * (2.0 * Math.PI / SINE.length));
        }
    }

    private FastMath() {}

    public static float sin(double angle) {
        return sample((int) (long) (angle * PHASE_SCALE));
    }

    public static float cos(double angle) {
        return sample((int) (long) (angle * PHASE_SCALE + 16384.0));
    }

    public static float sample(int phase) {
        int index = (phase & 65535) >>> 4;
        float start = SINE[index];
        return start + (SINE[(index + 1) & 4095] - start) * ((phase & 15) * 0.0625f);
    }

    public static int floor(double value) {
        int truncated = (int) value;
        return value < truncated && truncated != Integer.MIN_VALUE ? truncated - 1 : truncated;
    }

    public static int ceil(double value) {
        int truncated = (int) value;
        return value > truncated && truncated != Integer.MAX_VALUE ? truncated + 1 : truncated;
    }

    public static int floorMod(int value, int divisor) {
        if (divisor > 0 && (divisor & (divisor - 1)) == 0) {
            return value & (divisor - 1);
        }
        return Math.floorMod(value, divisor);
    }

    public static int sectionIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
