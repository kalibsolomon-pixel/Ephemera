package com.ephemera.render.terrain;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * Per-frame upload pacing seam for {@code UberGpuBuffer}, allowing
 * {@code SectionRenderDispatcher.uploadTerrainBuffersToGpu()} to defer overflow staged meshes
 * atomically across matching {@code vertexBuffer}/{@code indexBuffer} pairs without freeing
 * previously drawable allocations early.
 */
public interface UberGpuBufferBudgetControl {
	int ephemera$stagedCount();

	void ephemera$pickOverflowKeys(int maxAdmit, ObjectOpenHashSet<Object> outDeferred);

	void ephemera$deferKeys(ObjectOpenHashSet<Object> deferredKeys);

	void ephemera$restoreDeferred();
}
