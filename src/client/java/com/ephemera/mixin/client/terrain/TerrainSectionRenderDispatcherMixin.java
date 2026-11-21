package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.render.terrain.SectionDispatcherEpoch;
import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.UberGpuBufferSlot;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Tags the dispatcher's six uber buffers with their (layer, vertex|index) slot and a fresh
 * epoch, enabling the allocation mirror. Meshes from a previous dispatcher carry an older
 * epoch and are therefore ignored, exactly as vanilla's per-dispatcher maps would ignore them.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class TerrainSectionRenderDispatcherMixin implements SectionDispatcherEpoch {
	@Shadow
	@Final
	private Map<ChunkSectionLayer, Object> chunkUberBuffers;

	@Unique
	private int ephemera$epoch;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$tagUberBuffers(TracingExecutor executor, RenderBuffers renderBuffers, SectionCompiler sectionCompiler,
										 Consumer<SectionRenderDispatcher.RenderSection> onSectionMeshUpdate, CallbackInfo ci) {
		int epoch = NEXT_EPOCH.getAndIncrement();
		for (Map.Entry<ChunkSectionLayer, Object> entry : chunkUberBuffers.entrySet()) {
			if (!(entry.getValue() instanceof TerrainSectionUberBuffersAccessor buffers)
				|| !(buffers.getEphemeraVertexBuffer() instanceof UberGpuBufferSlot vertex)
				|| !(buffers.getEphemeraIndexBuffer() instanceof UberGpuBufferSlot index)) {
				return; // leave epoch at 0: fast path disabled for this dispatcher
			}
			int layer = entry.getKey().ordinal();
			vertex.ephemera$assignSlot(SectionMeshRenderCache.vertexSlot(layer), epoch);
			index.ephemera$assignSlot(SectionMeshRenderCache.indexSlot(layer), epoch);
		}
		ephemera$epoch = epoch;
	}

	/** Releases the batcher's indirect command buffers and drops its frame plan with the dispatcher. */
	@Inject(method = "dispose", at = @At("HEAD"))
	private void ephemera$releaseBatcher(CallbackInfo ci) {
		ChunkRenderBatcher.get().release();
	}

	@Override
	public int ephemera$epoch() {
		return ephemera$epoch;
	}
}
