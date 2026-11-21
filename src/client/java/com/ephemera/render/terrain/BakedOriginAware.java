package com.ephemera.render.terrain;

/**
 * Marker mixed into {@code LevelRenderer} together with the vanilla
 * {@code prepareChunkRenders} origin fix, so region-baked meshes stay correctly placed even
 * when the chunk batcher is bypassed.
 */
public interface BakedOriginAware {
}
