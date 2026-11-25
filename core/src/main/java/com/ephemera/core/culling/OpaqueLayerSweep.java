package com.ephemera.core.culling;

/** A bounded, conservative proof of occlusion, not a sample of a few rays. */
public final class OpaqueLayerSweep {
    @FunctionalInterface
    public interface Solidity { boolean isOpaque(int x, int y, int z); }

    private static final double EPSILON = 1.0e-7;
    private final Solidity solidity;
    private final int maxTests;
    private int remaining;
    private int lastTestsUsed;

    public OpaqueLayerSweep(Solidity solidity) { this(solidity, 512); }
    public OpaqueLayerSweep(Solidity solidity, int maxTests) {
        if (solidity == null || maxTests < 1) throw new IllegalArgumentException();
        this.solidity = solidity;
        this.maxTests = maxTests;
    }

    /** Number of {@link Solidity#isOpaque} probes consumed by the most recent {@link #isOccluded} call. */
    public int lastTestsUsed() { return lastTestsUsed; }

    /** Inputs are the already expanded camera cube and target bounds. No retained world data. */
    public boolean isOccluded(double cx0, double cy0, double cz0, double cx1, double cy1, double cz1,
                              double x0, double y0, double z0, double x1, double y1, double z1) {
        lastTestsUsed = 0;
        if (!valid(cx0, cx1) || !valid(cy0, cy1) || !valid(cz0, cz1)
                || !valid(x0, x1) || !valid(y0, y1) || !valid(z0, z1)) return false;
        remaining = maxTests;
        boolean hidden = axis(0, cx0, cx1, x0, x1, cy0, cy1, y0, y1, cz0, cz1, z0, z1)
                || axis(1, cy0, cy1, y0, y1, cx0, cx1, x0, x1, cz0, cz1, z0, z1)
                || axis(2, cz0, cz1, z0, z1, cx0, cx1, x0, x1, cy0, cy1, y0, y1);
        lastTestsUsed = maxTests - remaining;
        return hidden;
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
        // Center-to-center ray lies strictly inside both endpoint volumes; any separating opaque
        // layer MUST block it, so testing that single center voxel first rejects open-air layers
        // before doing 16 perspective divisions and a full rectangle sweep.
        double cc = (c0 + c1) * 0.5, tc = (t0 + t1) * 0.5;
        double invSpan = 1.0 / (tc - cc);
        double cca = (ca0 + ca1) * 0.5, dca = ((ta0 + ta1) * 0.5 - cca) * invSpan;
        double ccb = (cb0 + cb1) * 0.5, dcb = ((tb0 + tb1) * 0.5 - ccb) * invSpan;
        // Walk from the target toward the camera; nearby walls have smaller projections.
        int step = c1 < t0 ? -1 : 1;
        int layer = step < 0 ? last : first;
        for (int n = 0; n < 32 && layer >= first && layer <= last && remaining > 0; n++, layer += step) {
            double p = layer + .5;
            double dp = p - cc;
            int midA = fastFloor(cca + dca * dp);
            int midB = fastFloor(ccb + dcb * dp);
            remaining--;
            boolean midOpaque = switch (axis) {
                case 0 -> solidity.isOpaque(layer, midA, midB);
                case 1 -> solidity.isOpaque(midA, layer, midB);
                default -> solidity.isOpaque(midA, midB, layer);
            };
            if (!midOpaque) continue;

            // Perspective extrema are at endpoint corners: each is linear-fractional in c/t.
            double a0 = projected(p, c0, c1, t0, t1, ca0, ta0, true);
            double a1 = projected(p, c0, c1, t0, t1, ca1, ta1, false);
            double b0 = projected(p, c0, c1, t0, t1, cb0, tb0, true);
            double b1 = projected(p, c0, c1, t0, t1, cb1, tb1, false);
            int ia0 = fastFloor(a0 - EPSILON), ia1 = fastFloor(a1 + EPSILON);
            int ib0 = fastFloor(b0 - EPSILON), ib1 = fastFloor(b1 + EPSILON);
            long cells = ((long) ia1 - ia0 + 1) * ((long) ib1 - ib0 + 1);
            if (cells <= 1L) return true; // Single-voxel footprint was already proven opaque by midOpaque!
            long needed = cells - ((midA >= ia0 && midA <= ia1 && midB >= ib0 && midB <= ib1) ? 1L : 0L);
            if (needed > remaining) continue;
            boolean full = true;
            outer: for (int a = ia0; a <= ia1; a++) for (int b = ib0; b <= ib1; b++) {
                if (a == midA && b == midB) continue;
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

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
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
