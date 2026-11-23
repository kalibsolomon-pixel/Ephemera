package com.ephemera.render.terrain;

import com.mojang.blaze3d.vertex.TlsfAllocator;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.jspecify.annotations.Nullable;

/**
 * Duck interface mixed into 26.3 {@code CompiledSectionMesh}. Holds direct ordinal-indexed
 * references replacing vanilla's per-frame {@code HashMap} lookups in
 * {@code UberGpuBuffer.getAllocation} and {@code SectionRenderDispatcher.getRenderSectionSlice},
 * plus precomputed {@link SectionDrawState} and Vulkan meshlet arena handle.
 */
public interface SectionMeshRenderCache {
	int SLOT_COUNT = 6;

	static int vertexSlot(int layerOrdinal) {
		return layerOrdinal << 1;
	}

	static int indexSlot(int layerOrdinal) {
		return (layerOrdinal << 1) | 1;
	}

	/** Allocation in {@code slot}, or null when absent or owned by another dispatcher epoch. */
	TlsfAllocator.@Nullable Allocation ephemera$allocation(int slot, int epoch);

	/**
	 * Mirrors an allocation change and recomputes the affected layer of
	 * {@link #ephemera$drawState}. Also bumps {@link TerrainTopology#topology()}.
	 */
	void ephemera$setAllocation(int slot, int epoch, TlsfAllocator.@Nullable Allocation allocation);

	/** Precomputed draw parameters for {@code epoch}, or null when nothing was uploaded for it. */
	@Nullable SectionDrawState ephemera$drawState(int epoch);

	/** Cached ready-layer bitmask for {@code epoch} (0 when absent) without chasing {@code SectionDrawState}. */
	int ephemera$readyMask(int epoch);

	/** True when {@code ephemera$sectionX/Y/Z} hold valid section coordinates from layout. */
	boolean ephemera$hasSectionCoords();

	int ephemera$sectionX();

	int ephemera$sectionY();

	int ephemera$sectionZ();

	/** True when animated sprites on this mesh must be stamped for {@code spriteGeneration}. */
	boolean ephemera$needsSpriteStamp(int spriteGeneration);

	/** Wall-clock millis when this mesh's sprites were last stamped into {@link VisibleSpriteTracker}. */
	long ephemera$lastSpriteStampMs();

	void ephemera$setLastSpriteStampMs(long nowMs);

	/** Vanilla {@code getSectionDraw(layer)} without the {@code EnumMap} lookup. */
	SectionMesh.@Nullable SectionDraw ephemera$draw(int layerOrdinal);

	boolean ephemera$hasDraws();

	/** Vertex layout this mesh was baked with, or null for vanilla section-local layout. */
	@Nullable SectionLayout ephemera$layout();

	/** Optional GPU meshlet arena handle when rendered via Vulkan {@code VK_EXT_mesh_shader}. */
	com.ephemera.render.terrain.vulkan.@Nullable VulkanMeshSectionHandle ephemera$meshHandle();

	void ephemera$setMeshHandle(com.ephemera.render.terrain.vulkan.@Nullable VulkanMeshSectionHandle handle);

	/** Animated block-atlas sprite ids referenced by this mesh's block models. */
	int[] ephemera$animatedSprites();

	/** {@link VisibleSpriteTracker} generation the ids belong to, or -1 when never recorded. */
	int ephemera$spriteGeneration();

	/** True when the mesh contains models whose sprites could not be enumerated. */
	boolean ephemera$untrackedSprites();
}
