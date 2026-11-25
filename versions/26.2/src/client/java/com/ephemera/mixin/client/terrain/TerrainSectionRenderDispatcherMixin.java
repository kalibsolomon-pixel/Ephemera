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
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
	@Shadow @Final @Mutable private TracingExecutor executor;

	@Unique private int ephemera$epoch;
	@Unique private ObjectOpenHashSet<Object> ephemera$deferredKeys;
	@Unique private ExecutorService ephemera$dedicatedChunkPool;

	@Unique private static final AtomicLong EPHEMERA$CHUNK_BUILD_THREAD = new AtomicLong();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$tagUberBuffers(TracingExecutor executor, RenderBuffers renderBuffers, SectionCompiler sectionCompiler,
										 Consumer<SectionRenderDispatcher.RenderSection> onSectionMeshUpdate, CallbackInfo ci) {
		this.ephemera$deferredKeys = new ObjectOpenHashSet<>(64);
		ephemera$swapInConfiguredChunkBuilderPool();
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

	/**
	 * Wires the {@code chunkBuilderThreads} setting: {@code 0} (auto) keeps vanilla's shared
	 * {@code Util.backgroundExecutor()} unchanged, and {@code 1..32} swaps in a dedicated fixed
	 * pool so chunk builds stop competing with worldgen/resource-load storms during streaming.
	 * The ctor only stores the executor (no task was submitted yet), so swapping the final field
	 * here is unobservable to any other reader. Threads run at {@code NORM_PRIORITY - 1} to match
	 * the shared pool's {@code UtilWorkerPriorityMixin} policy (never preempting render/server
	 * threads), and use {@link ThreadPoolExecutor.DiscardPolicy} because vanilla's
	 * {@code runTask()} self-reschedules via {@code executor.execute(...)} after {@code dispose()}
	 * without re-checking {@code closed} and escalates {@code RejectedExecutionException} into a
	 * client crash — discarding instead keeps world switch / reload teardown safe.
	 */
	@Unique
	private void ephemera$swapInConfiguredChunkBuilderPool() {
		int configured = EphemeraCommon.config().chunkBuilderThreads;
		if (configured <= 0) {
			return; // auto: keep vanilla's shared background executor exactly
		}
		try {
			int threads = Math.max(1, Math.min(configured, Runtime.getRuntime().availableProcessors()));
			ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads,
				30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
				r -> {
					Thread t = new Thread(r, "Ephemera-ChunkBuild-" + EPHEMERA$CHUNK_BUILD_THREAD.incrementAndGet());
					t.setDaemon(true);
					t.setPriority(Thread.NORM_PRIORITY - 1);
					return t;
				},
				new ThreadPoolExecutor.DiscardPolicy());
			pool.allowCoreThreadTimeOut(true);
			this.ephemera$dedicatedChunkPool = pool;
			this.executor = new TracingExecutor(pool);
		} catch (Throwable t) {
			// Fail-soft: keep vanilla's shared executor rather than breaking chunk building.
			this.ephemera$dedicatedChunkPool = null;
			EphemeraCommon.LOGGER.debug("Dedicated chunk builder pool unavailable: {}", t.toString());
		}
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
				ObjectOpenHashSet<Object> deferredKeys = this.ephemera$deferredKeys;
				if (deferredKeys == null) {
					this.ephemera$deferredKeys = deferredKeys = new ObjectOpenHashSet<>(64);
				}
				if (vStaged > layerCap) {
					deferredKeys.clear();
					vCtrl.ephemera$pickOverflowKeys(layerCap, deferredKeys);
					if (!deferredKeys.isEmpty()) {
						vCtrl.ephemera$deferKeys(deferredKeys);
						iCtrl.ephemera$deferKeys(deferredKeys);
						deferred = true;
					}
				} else if (vStaged == 0 && iStaged > layerCap) {
					deferredKeys.clear();
					iCtrl.ephemera$pickOverflowKeys(layerCap, deferredKeys);
					if (!deferredKeys.isEmpty()) {
						iCtrl.ephemera$deferKeys(deferredKeys);
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
						deferredKeys.clear();
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
		ExecutorService pool = ephemera$dedicatedChunkPool;
		ephemera$dedicatedChunkPool = null;
		if (pool != null) {
			// Non-blocking: vanilla's shared executor is never shut down here, only our dedicated
			// pool; daemon threads + DiscardPolicy make in-flight post-shutdown reschedules safe.
			pool.shutdown();
		}
	}

	@Override
	public int ephemera$epoch() {
		return ephemera$epoch;
	}
}
