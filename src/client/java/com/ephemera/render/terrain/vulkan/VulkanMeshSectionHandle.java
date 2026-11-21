package com.ephemera.render.terrain.vulkan;

/**
 * Render-thread handle linking one {@code CompiledSectionMesh} to its persistent device-local
 * allocations inside {@link VulkanMeshArena}.
 */
public final class VulkanMeshSectionHandle {
	public final int arenaEpoch;
	public final int slot;
	public final long quadHandle;
	public final long meshletHandle;
	/** Bitmask of layers hosted in the GPU meshlet arena (bit 0 = SOLID, bit 1 = CUTOUT). */
	public final int layerMask;
	public final int solidMeshletCount;
	public final int cutoutMeshletCount;
	public float lastVisibility;
	boolean released;

	VulkanMeshSectionHandle(int arenaEpoch, int slot, long quadHandle, long meshletHandle,
							int layerMask, int solidMeshletCount, int cutoutMeshletCount, float lastVisibility) {
		this.arenaEpoch = arenaEpoch;
		this.slot = slot;
		this.quadHandle = quadHandle;
		this.meshletHandle = meshletHandle;
		this.layerMask = layerMask;
		this.solidMeshletCount = solidMeshletCount;
		this.cutoutMeshletCount = cutoutMeshletCount;
		this.lastVisibility = lastVisibility;
	}
}
