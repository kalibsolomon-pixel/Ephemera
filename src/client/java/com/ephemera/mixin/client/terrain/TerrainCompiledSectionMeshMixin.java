package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.CompileResultsSprites;
import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Gives every compiled section mesh direct, ordinal-indexed fields for its draws and GPU
 * allocations so the chunk batcher never touches {@code EnumMap}/{@code HashMap} lookups.
 * Arrays are created lazily so meshes without geometry stay as small as vanilla's.
 */
@Mixin(CompiledSectionMesh.class)
public abstract class TerrainCompiledSectionMeshMixin implements SectionMeshRenderCache {
	@Unique
	private static final int LAYER_COUNT = ChunkSectionLayer.values().length;
	@Unique
	private static final int[] EPHEMERA$NO_SPRITES = new int[0];

	@Shadow
	@Final
	private Map<ChunkSectionLayer, SectionMesh.SectionDraw> draws;

	@Unique
	private SectionMesh.SectionDraw @Nullable [] ephemera$draws;
	@Unique
	private TlsfAllocator.Allocation @Nullable [] ephemera$allocations;
	@Unique
	private int ephemera$epoch;
	@Unique
	private RenderPass.Draw<GpuBufferSlice[]> @Nullable [] ephemera$drawCache;
	@Unique
	private int @Nullable [] ephemera$drawCacheUbo;
	@Unique
	private TlsfAllocator.Allocation @Nullable [] ephemera$drawCacheAlloc;
	@Unique
	private DynamicUniforms.@Nullable ChunkSectionInfo ephemera$info;
	@Unique
	private int[] ephemera$sprites = EPHEMERA$NO_SPRITES;
	@Unique
	private int ephemera$spriteGeneration = -1;
	@Unique
	private boolean ephemera$untrackedSprites;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$captureDraws(TranslucencyPointOfView translucencyPointOfView, SectionCompiler.Results results, CallbackInfo ci) {
		if (!draws.isEmpty()) {
			SectionMesh.SectionDraw[] flat = new SectionMesh.SectionDraw[LAYER_COUNT];
			for (Map.Entry<ChunkSectionLayer, SectionMesh.SectionDraw> e : draws.entrySet()) {
				flat[e.getKey().ordinal()] = e.getValue();
			}
			ephemera$draws = flat;
		}
		if ((Object) results instanceof CompileResultsSprites sprites) {
			ephemera$sprites = sprites.ephemera$animatedSprites();
			ephemera$spriteGeneration = sprites.ephemera$spriteGeneration();
			ephemera$untrackedSprites = sprites.ephemera$untrackedSprites();
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void ephemera$dropDraws(CallbackInfo ci) {
		ephemera$draws = null;
		ephemera$drawCache = null;
		ephemera$drawCacheUbo = null;
		ephemera$drawCacheAlloc = null;
		ephemera$info = null;
	}

	@Override
	public TlsfAllocator.@Nullable Allocation ephemera$allocation(int slot, int epoch) {
		TlsfAllocator.Allocation[] allocations = ephemera$allocations;
		return allocations != null && epoch == ephemera$epoch ? allocations[slot] : null;
	}

	@Override
	public void ephemera$setAllocation(int slot, int epoch, TlsfAllocator.@Nullable Allocation allocation) {
		TlsfAllocator.Allocation[] allocations = ephemera$allocations;
		if (allocations == null || epoch != ephemera$epoch) {
			if (allocation == null) {
				return;
			}
			allocations = new TlsfAllocator.Allocation[SLOT_COUNT];
			ephemera$allocations = allocations;
			ephemera$epoch = epoch;
		}
		allocations[slot] = allocation;
	}

	@Override
	public SectionMesh.@Nullable SectionDraw ephemera$draw(int layerOrdinal) {
		SectionMesh.SectionDraw[] flat = ephemera$draws;
		return flat != null ? flat[layerOrdinal] : null;
	}

	@Override
	public boolean ephemera$hasDraws() {
		return ephemera$draws != null;
	}

	@Override
	public RenderPass.@Nullable Draw<GpuBufferSlice[]> ephemera$cachedDraw(int layerOrdinal, int uboIndex,
		TlsfAllocator.Allocation vertex, TlsfAllocator.@Nullable Allocation index) {
		RenderPass.Draw<GpuBufferSlice[]>[] cache = ephemera$drawCache;
		if (cache == null) {
			return null;
		}
		int[] ubo = ephemera$drawCacheUbo;
		TlsfAllocator.Allocation[] alloc = ephemera$drawCacheAlloc;
		int a = layerOrdinal << 1;
		if (ubo[layerOrdinal] != uboIndex || alloc[a] != vertex || alloc[a + 1] != index) {
			return null;
		}
		return cache[layerOrdinal];
	}

	@Override
	@SuppressWarnings("unchecked")
	public void ephemera$cacheDraw(int layerOrdinal, int uboIndex, TlsfAllocator.Allocation vertex,
		TlsfAllocator.@Nullable Allocation index, RenderPass.Draw<GpuBufferSlice[]> draw) {
		if (ephemera$drawCache == null) {
			ephemera$drawCache = new RenderPass.Draw[LAYER_COUNT];
			ephemera$drawCacheUbo = new int[LAYER_COUNT];
			ephemera$drawCacheAlloc = new TlsfAllocator.Allocation[SLOT_COUNT];
		}
		int a = layerOrdinal << 1;
		ephemera$drawCache[layerOrdinal] = draw;
		ephemera$drawCacheUbo[layerOrdinal] = uboIndex;
		ephemera$drawCacheAlloc[a] = vertex;
		ephemera$drawCacheAlloc[a + 1] = index;
	}

	@Override
	public DynamicUniforms.@Nullable ChunkSectionInfo ephemera$cachedInfo() {
		return ephemera$info;
	}

	@Override
	public void ephemera$cacheInfo(DynamicUniforms.ChunkSectionInfo info) {
		ephemera$info = info;
	}

	@Override
	public int[] ephemera$animatedSprites() {
		return ephemera$sprites;
	}

	@Override
	public int ephemera$spriteGeneration() {
		return ephemera$spriteGeneration;
	}

	@Override
	public boolean ephemera$untrackedSprites() {
		return ephemera$untrackedSprites;
	}
}
