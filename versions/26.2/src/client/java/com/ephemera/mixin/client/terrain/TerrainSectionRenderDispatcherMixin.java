package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.render.terrain.SectionDispatcherEpoch;
import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.UberGpuBufferBudgetControl;
import com.ephemera.render.terrain.UberGpuBufferSlot;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
 * Tags the dispatcher's uber buffers with their (layer, vertex|index) slot and epoch, and paces
 * indexed GPU terrain uploads inside {@code uploadTerrainBuffersToGpu()} against the shared frame
 * streaming budget so rebuild/flight bursts never dump hundreds of meshes in one frame on either
 * OpenGL or Vulkan.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class TerrainSectionRenderDispatcherMixin implements SectionDispatcherEpoch {
	@Shadow @Final private Map<ChunkSectionLayer, Object> chunkUberBuffers;
	@Shadow @Final private StagingBuffer stagingBuffer;

	@Unique private int ephemera$epoch;
	@Unique private final ObjectOpenHashSet<Object> ephemera$deferredKeys = new ObjectOpenHashSet<>(64);

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

	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"), cancellable = true)
	private void ephemera$pacedUploadTerrainBuffers(CallbackInfo ci) {
		if (!EphemeraCommon.config().framePacerAdaptiveUploadBudget) {
			return;
		}
		int totalStaged = 0;
		for (Object raw : chunkUberBuffers.values()) {
			if (!(raw instanceof TerrainSectionUberBuffersAccessor buffers)
				|| !(buffers.getEphemeraVertexBuffer() instanceof UberGpuBufferBudgetControl vCtrl)
				|| !(buffers.getEphemeraIndexBuffer() instanceof UberGpuBufferBudgetControl iCtrl)) {
				return;
			}
			totalStaged += vCtrl.ephemera$stagedCount() + iCtrl.ephemera$stagedCount();
		}
		if (totalStaged == 0) {
			ci.cancel();
			return;
		}

		EphemeraPacerRuntime.beginFrameStreamingBudget();
		FrameBudgetPacer budget = EphemeraPacerRuntime.budget();
		boolean capped = budget.effectiveTargetFps() > 0;
		long remNs = budget.remainingStreamingHeadroomNs(System.nanoTime());
		// On FPS-capped frames, enforce a tight cross-layer total cap so bursts never overrun targetPresent.
		// On uncapped frames, cap per-layer (never starving CUTOUT/TRANSLUCENT behind SOLID) so newly built
		// chunks drain cleanly and ChunkRenderBatcher immediately locks into draw-plan reuse.
		int remainingBudget = capped
			? (remNs >= 1_200_000L ? 12 : (remNs >= 500_000L ? 6 : (remNs >= 150_000L ? 3 : 1)))
			: Integer.MAX_VALUE;
		int uncappedPerLayer = remNs >= 800_000L ? 24 : 12;
		int totalAdmitted = 0;

		GpuDevice device = RenderSystem.getDevice();
		try (StagingBuffer.Uploader uploader = this.stagingBuffer.startUploading(device.createCommandEncoder())) {
			for (Object raw : chunkUberBuffers.values()) {
				if (capped && remainingBudget <= 0) {
					break;
				}
				if (capped && totalAdmitted > 0 && budget.remainingStreamingHeadroomNs(System.nanoTime()) <= 0L) {
					break;
				}
				TerrainSectionUberBuffersAccessor buffers = (TerrainSectionUberBuffersAccessor) raw;
				UberGpuBuffer<?> vBuf = buffers.getEphemeraVertexBuffer();
				UberGpuBuffer<?> iBuf = buffers.getEphemeraIndexBuffer();
				UberGpuBufferBudgetControl vCtrl = (UberGpuBufferBudgetControl) vBuf;
				UberGpuBufferBudgetControl iCtrl = (UberGpuBufferBudgetControl) iBuf;
				int vStaged = vCtrl.ephemera$stagedCount();
				int iStaged = iCtrl.ephemera$stagedCount();
				if (vStaged == 0 && iStaged == 0) {
					continue;
				}
				int layerCap = capped ? remainingBudget : uncappedPerLayer;
				int admittedCount = Math.min(Math.max(vStaged, iStaged), layerCap);
				boolean deferred = false;
				if (vStaged > layerCap) {
					this.ephemera$deferredKeys.clear();
					vCtrl.ephemera$pickOverflowKeys(layerCap, this.ephemera$deferredKeys);
					if (!this.ephemera$deferredKeys.isEmpty()) {
						vCtrl.ephemera$deferKeys(this.ephemera$deferredKeys);
						iCtrl.ephemera$deferKeys(this.ephemera$deferredKeys);
						deferred = true;
					}
				} else if (vStaged == 0 && iStaged > layerCap) {
					this.ephemera$deferredKeys.clear();
					iCtrl.ephemera$pickOverflowKeys(layerCap, this.ephemera$deferredKeys);
					if (!this.ephemera$deferredKeys.isEmpty()) {
						iCtrl.ephemera$deferKeys(this.ephemera$deferredKeys);
						deferred = true;
					}
				}
				boolean createdNewHeap;
				try {
					long t0 = System.nanoTime();
					createdNewHeap = vBuf.uploadStagedAllocations(device, uploader);
					iBuf.uploadStagedAllocations(device, uploader);
					budget.recordStreamingWork(System.nanoTime() - t0);
				} finally {
					if (deferred) {
						vCtrl.ephemera$restoreDeferred();
						iCtrl.ephemera$restoreDeferred();
						this.ephemera$deferredKeys.clear();
					}
				}
				if (capped) {
					int consumed = Math.max(1, admittedCount);
					totalAdmitted += consumed;
					remainingBudget -= consumed;
				}
				if (createdNewHeap) {
					break;
				}
			}
		}
		ci.cancel();
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
