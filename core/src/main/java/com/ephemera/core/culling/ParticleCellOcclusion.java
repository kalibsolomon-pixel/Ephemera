package com.ephemera.core.culling;

/** Bounded dominant-axis DDA, proving a whole particle cell rather than sampling center rays. */
public final class ParticleCellOcclusion {
    private static final double EPSILON = 1.0e-7;
    private final OpaqueLayerSweep.Solidity solidity;
    private final int[] xs = new int[4096], ys = new int[4096], zs = new int[4096];
    private final long[] epochs = new long[4096];
    private final boolean[] hidden = new boolean[4096];
    private long epoch = 1, frame = Long.MIN_VALUE, stamp;
    private double cameraX, cameraY, cameraZ;
    private boolean cameraValid;

    public ParticleCellOcclusion(OpaqueLayerSweep.Solidity solidity) {
        if (solidity == null) throw new IllegalArgumentException("solidity required");
        this.solidity = solidity;
    }

    /** Updates camera/terrain epoch once per frame so per-particle queries skip repeated camera checks. */
    public boolean prepareFrame(double cx, double cy, double cz, long frame, long terrainStamp) {
        if (!valid(cx) || !valid(cy) || !valid(cz)) {
            cameraValid = false;
            return false;
        }
        cameraValid = true;
        if (stamp != terrainStamp || cameraX != cx || cameraY != cy || cameraZ != cz) {
            this.frame = frame; stamp = terrainStamp; cameraX = cx; cameraY = cy; cameraZ = cz;
            epoch++;
        }
        return true;
    }

    /** Fast per-particle probe after {@link #prepareFrame}; caller already verified finite coordinates. */
    public boolean isOccludedFast(double x, double y, double z, double radius) {
        if (!cameraValid || !(radius >= 0 && radius <= .5)
                || Math.abs(x) >= 30_000_064 || Math.abs(y) >= 30_000_064 || Math.abs(z) >= 30_000_064) return false;
        double cx = cameraX, cy = cameraY, cz = cameraZ;
        double dx = x - cx, dy = y - cy, dz = z - cz;
        if (dx * dx + dy * dy + dz * dz < 9) return false;
        int bx = fastFloor(x), by = fastFloor(y), bz = fastFloor(z);
        int slot = (bx * 73428767 ^ by * 912931 ^ bz * 438289) & 4095;
        if (epochs[slot] == epoch && xs[slot] == bx && ys[slot] == by && zs[slot] == bz) return hidden[slot];
        double ax = Math.abs(bx + .5 - cx), ay = Math.abs(by + .5 - cy), az = Math.abs(bz + .5 - cz);
        boolean result;
        if (ax >= ay && ax >= az) result = probe(0, cx, cy, cz, bx, by, bz);
        else if (ay >= az) result = probe(1, cy, cx, cz, by, bx, bz);
        else result = probe(2, cz, cx, cy, bz, bx, by);
        xs[slot] = bx; ys[slot] = by; zs[slot] = bz; epochs[slot] = epoch; hidden[slot] = result;
        return result;
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public boolean isOccluded(double cx, double cy, double cz, double x, double y, double z,
                              double radius, long frame, long terrainStamp) {
        if (!prepareFrame(cx, cy, cz, frame, terrainStamp)
                || !Double.isFinite(x + y + z + radius)) return false;
        return isOccludedFast(x, y, z, radius);
    }

    private static boolean valid(double value) { return Double.isFinite(value) && Math.abs(value) < 30_000_064; }

    private boolean probe(int axis, double c, double ca, double cb, int target, int a, int b) {
        double t0 = target - .75, t1 = target + 1.75;
        if (!(c < t0 || c > t1)) return false;
        int step = c < t0 ? -1 : 1;
        int layer = step < 0 ? (int) Math.ceil(t0) - 2 : (int) Math.floor(t1) + 1;
        for (int n = 0; n < 16; n++, layer += step) {
            // A complete solid cell lies between camera and all possible target points.
            if (!(c < layer && layer + 1 < t0 || t1 < layer && layer + 1 < c)) break;
            double p = layer + .5;
            double u = (p - c) / (t0 - c), v = (p - c) / (t1 - c);
            int a0 = (int) Math.floor(Math.min(ca + (a - .75 - ca) * u, ca + (a - .75 - ca) * v) - EPSILON);
            int a1 = (int) Math.floor(Math.max(ca + (a + 1.75 - ca) * u, ca + (a + 1.75 - ca) * v) + EPSILON);
            int b0 = (int) Math.floor(Math.min(cb + (b - .75 - cb) * u, cb + (b - .75 - cb) * v) - EPSILON);
            int b1 = (int) Math.floor(Math.max(cb + (b + 1.75 - cb) * u, cb + (b + 1.75 - cb) * v) + EPSILON);
            if (a1 - a0 > 3 || b1 - b0 > 3) continue; // <= 16 cells/step, <= 256 total.
            boolean full = true;
            outer: for (int ia = a0; ia <= a1; ia++) for (int ib = b0; ib <= b1; ib++) {
                boolean opaque = switch (axis) {
                    case 0 -> solidity.isOpaque(layer, ia, ib);
                    case 1 -> solidity.isOpaque(ia, layer, ib);
                    default -> solidity.isOpaque(ia, ib, layer);
                };
                if (!opaque) { full = false; break outer; }
            }
            if (full) return true;
        }
        return false;
    }
}
