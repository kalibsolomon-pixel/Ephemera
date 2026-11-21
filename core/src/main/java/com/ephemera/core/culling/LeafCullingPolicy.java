package com.ephemera.core.culling;

/** Cutout leaves are never treated as opaque: depth alone is not proof of invisibility. */
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
                    && provenOpaqueDepth >= Math.max(1, Math.min(8, requestedDepth));
        };
    }
}
