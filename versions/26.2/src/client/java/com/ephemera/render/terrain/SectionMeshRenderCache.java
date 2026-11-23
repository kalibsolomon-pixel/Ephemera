package com.ephemera.render.terrain;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.jspecify.annotations.Nullable;

/**
 * Duck interface mixed into {@code CompiledSectionMesh}. Holds direct references that
 * replace the per-frame {@code HashMap} lookups vanilla performs through
 * {@code UberGpuBuffer.getAllocation} and {@code SectionRenderDispatcher.getRenderSectionSlice},
 * plus per-mesh caches the batcher reuses while a section's draw state is unchanged.
 *
 * <p>Allocation slots are indexed {@code layer.ordinal() * 2} (vertex) and
 * {@code layer.ordinal() * 2 + 1} (index). They are written under the dispatcher's copy
 * lock by the {@code UberGpuBuffer} mirror hooks and read under the same lock.
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

	/** Last wall-clock millisecond this mesh's animated sprites were stamped. */
	long ephemera$lastSpriteStampMs();

	void ephemera$setLastSpriteStampMs(long nowMs);

	/** Vanilla {@code getSectionDraw(layer)} without the {@code EnumMap} lookup. */
	SectionMesh.@Nullable SectionDraw ephemera$draw(int layerOrdinal);

	boolean ephemera$hasDraws();

	RenderPass.@Nullable Draw<GpuBufferSlice[]> ephemera$cachedDraw(int layerOrdinal, int uboIndex,
		TlsfAllocator.Allocation vertex, TlsfAllocator.@Nullable Allocation index);

	void ephemera$cacheDraw(int layerOrdinal, int uboIndex, TlsfAllocator.Allocation vertex,
		TlsfAllocator.@Nullable Allocation index, RenderPass.Draw<GpuBufferSlice[]> draw);

	DynamicUniforms.@Nullable ChunkSectionInfo ephemera$cachedInfo();

	void ephemera$cacheInfo(DynamicUniforms.ChunkSectionInfo info);

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
