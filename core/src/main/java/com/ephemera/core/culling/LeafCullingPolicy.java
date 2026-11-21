package com.ephemera.core.culling;

/** Policy decisions for interior leaf culling (both whole-block and per-face depth modes). */
public final class LeafCullingPolicy {
    public enum Mode { OFF, CHECK_SURROUNDING, DEPTH, FAST_OPAQUE }
    private LeafCullingPolicy() { }

    public static boolean skip(Mode mode, boolean opaqueLeaves, boolean adjacentOpaqueLeaf,
                               int opaqueSurroundingMask, int provenOpaqueDepth, int requestedDepth) {
        return switch (mode) {
            case OFF -> false;
            case FAST_OPAQUE -> opaqueLeaves && adjacentOpaqueLeaf;
            case CHECK_SURROUNDING -> (opaqueSurroundingMask & 63) == 63;
            case DEPTH -> (opaqueSurroundingMask & 63) == 63
                    && provenOpaqueDepth >= clampDepth(requestedDepth);
        };
    }

    /**
     * Per-face leaf depth culling: skips a single leaf face when {@code consecutiveCoverDepth}
     * blocks along that face's outward normal are continuous leaf or solid opaque cover (or an
     * opaque solid wall was reached within the configured depth).
     */
    public static boolean skipFace(Mode mode, boolean hitOpaqueWall, int consecutiveCoverDepth, int requestedDepth) {
        if (mode != Mode.DEPTH) {
            return false;
        }
        return hitOpaqueWall || consecutiveCoverDepth >= clampDepth(requestedDepth);
    }

    public static int clampDepth(int requestedDepth) {
        return Math.max(1, Math.min(8, requestedDepth));
    }
}
