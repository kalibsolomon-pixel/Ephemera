package com.ephemera.render.terrain;

import org.jspecify.annotations.Nullable;

/**
 * Marker on {@code RenderSection}: present when the hook that bumps
 * {@link TerrainTopology#topology()} on every section mesh swap was applied. Frame-plan
 * reuse, direct compiled-mesh lookup, and fast block-entity extraction depend on it.
 */
public interface SectionMeshSwapHook {
	long ephemera$fadeEndMs();

	/**
	 * Vanilla {@code getVisibility(now)} (1 once {@code now - uploadedTime >= fadeDuration},
	 * else the linear ramp), eased with {@code ChunkFadeMath.easeOutCubic} when
	 * {@code smoothChunkFade} (the caller reads that gate once per frame).
	 */
	float ephemera$visibility(long now, boolean smoothChunkFade);

	/** Compiled mesh cache directly mirrored from {@code sectionMesh}, or null when UNCOMPILED/EMPTY. */
	@Nullable SectionMeshRenderCache ephemera$compiledCache();

	/** True when the active {@code CompiledSectionMesh} contains at least one renderable block entity. */
	boolean ephemera$hasBlockEntities();
}
