package com.ephemera.render.terrain;

/**
 * Marker on {@code RenderSection}: present when the hook that bumps
 * {@link TerrainTopology#topology()} on every section mesh swap was applied. Frame-plan
 * reuse in the chunk batcher depends on it.
 */
public interface SectionMeshSwapHook {
}
