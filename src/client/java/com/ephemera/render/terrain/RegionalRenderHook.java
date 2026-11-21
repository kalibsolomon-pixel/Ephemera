package com.ephemera.render.terrain;

/**
 * Marker mixed into {@code ChunkSectionsToRender} by the {@code renderGroup} hook that
 * executes a {@link RegionalChunkBatchPlan}. Without it the batcher never emits a plan.
 */
public interface RegionalRenderHook {
}
