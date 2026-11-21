package com.ephemera.core.culling;

/** A bounded, conservative proof of occlusion, not a sample of a few rays. */
public final class OpaqueLayerSweep {
    @FunctionalInterface
    public interface Solidity { boolean isOpaque(int x, int y, int z); }

    private static final double EPSILON = 1.0e-7;
    private final Solidity solidity;
    private final int maxTests;
    private int remaining;

    public OpaqueLayerSweep(Solidity solidity) { this(solidity, 512); }
    public OpaqueLayerSweep(Solidity solidity, int maxTests) {
        if (solidity == null || maxTests < 1) throw new IllegalArgumentException();
        this.solidity = solidity;
        this.maxTests = maxTests;
    }

    /** Inputs are the already expanded camera cube and target bounds. No retained world data. */
    public boolean isOccluded(double cx0, double cy0, double cz0, double cx1, double cy1, double cz1,
                              double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!valid(cx0, cx1) || !valid(cy0, cy1) || !valid(cz0, cz1)
                || !valid(x0, x1) || !valid(y0, y1) || !valid(z0, z1)) return false;
        remaining = maxTests;
        return axis(0, cx0, cx1, x0, x1, cy0, cy1, y0, y1, cz0, cz1, z0, z1)
                || axis(1, cy0, cy1, y0, y1, cx0, cx1, x0, x1, cz0, cz1, z0, z1)
                || axis(2, cz0, cz1, z0, z1, cx0, cx1, x0, x1, cy0, cy1, y0, y1);
    }

    private static boolean valid(double min, double max) {
        return Double.isFinite(min) && Double.isFinite(max) && min <= max
                && min > -30_000_128 && max < 30_000_128;
    }

    private boolean axis(int axis, double c0, double c1, double t0, double t1,
                         double ca0, double ca1, double ta0, double ta1,
                         double cb0, double cb1, double tb0, double tb1) {
        // A complete voxel must fit strictly between both endpoint volumes.
        double gap0, gap1;
        if (c1 < t0) { gap0 = c1; gap1 = t0; }
        else if (t1 < c0) { gap0 = t1; gap1 = c0; }
        else return false;
        if (gap1 - gap0 < 1 || remaining <= 0) return false;
        int first = (int) Math.ceil(gap0 + EPSILON);
        int last = (int) Math.floor(gap1 - EPSILON) - 1;
        // Walk from the target toward the camera; nearby walls have smaller projections.
        int step = c1 < t0 ? -1 : 1;
        int layer = step < 0 ? last : first;
        for (int n = 0; n < 32 && layer >= first && layer <= last && remaining > 0; n++, layer += step) {
            double p = layer + .5;
            // Perspective extrema are at endpoint corners: each is linear-fractional in c/t.
            double a0 = projected(p, c0, c1, t0, t1, ca0, ta0, true);
            double a1 = projected(p, c0, c1, t0, t1, ca1, ta1, false);
            double b0 = projected(p, c0, c1, t0, t1, cb0, tb0, true);
            double b1 = projected(p, c0, c1, t0, t1, cb1, tb1, false);
            int ia0 = (int) Math.floor(a0 - EPSILON), ia1 = (int) Math.floor(a1 + EPSILON);
            int ib0 = (int) Math.floor(b0 - EPSILON), ib1 = (int) Math.floor(b1 + EPSILON);
            long cells = ((long) ia1 - ia0 + 1) * ((long) ib1 - ib0 + 1);
            if (cells > remaining) continue;
            boolean full = true;
            outer: for (int a = ia0; a <= ia1; a++) for (int b = ib0; b <= ib1; b++) {
                remaining--;
                boolean opaque = switch (axis) {
                    case 0 -> solidity.isOpaque(layer, a, b);
                    case 1 -> solidity.isOpaque(a, layer, b);
                    default -> solidity.isOpaque(a, b, layer);
                };
                if (!opaque) { full = false; break outer; }
            }
            if (full) return true;
        }
        return false;
    }

    private static double projected(double p, double c0, double c1, double t0, double t1,
                                    double c, double t, boolean min) {
        double result = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double ca = (i & 1) == 0 ? c0 : c1, ta = (i & 2) == 0 ? t0 : t1;
            double v = c + (t - c) * ((p - ca) / (ta - ca));
            result = min ? Math.min(result, v) : Math.max(result, v);
        }
        return result;
    }
}
