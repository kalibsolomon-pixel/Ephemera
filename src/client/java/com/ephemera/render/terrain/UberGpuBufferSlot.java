package com.ephemera.render.terrain;

/**
 * Duck interface mixed into {@code UberGpuBuffer}. A section dispatcher tags each of its
 * six uber buffers (vertex/index per layer) with a slot and its epoch so allocation
 * changes can be mirrored straight onto the owning {@link SectionMeshRenderCache}.
 */
public interface UberGpuBufferSlot {
	/** Slot index 0..5, or -1 when this buffer is not a terrain section buffer. */
	int ephemera$slot();

	int ephemera$epoch();

	void ephemera$assignSlot(int slot, int epoch);
}
