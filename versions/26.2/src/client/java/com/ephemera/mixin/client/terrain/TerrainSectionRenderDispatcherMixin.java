package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.mixin.client.backend.StagingBufferUsageAccessor;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.render.pacing.StagingFlushState;
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
import java.util.concurrent.locks.ReentrantLock;
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
	@Shadow @Final private ReentrantLock copyLock;

	@Unique private int ephemera$epoch;
	@Unique private ObjectOpenHashSet<Object> ephemera$deferredKeys;
	@Unique private ExecutorService ephemera$dedicatedChunkPool;

	@Unique private static final AtomicLong EPHEMERA$CHUNK_BUILD_THREAD = new AtomicLong();
	// Reusable rotated layer order for the paced upload (<= 4 layers; render-thread confined).
	@Unique private static final Object[] EPHEMERA$ROTATED_LAYERS = new Object[8];
	// 65% of vanilla's 102,760,448-byte "Chunk" staging arena: past this the next paced upload
	// bypasses per-layer caps and drains everything so the bump-pointer arena can rotate.
	@Unique private static final int EPHEMERA$STAGING_WATERMARK_BYTES = 102_760_448 * 65 / 100;

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

		// Defensive: every vanilla caller holds copyLock (reentrant, so free when held), and this
		// also protects staged-map deferral against unlocked third-party invocations.
		boolean heldLock = this.copyLock.isHeldByCurrentThread();
		if (!heldLock) {
			this.copyLock.lock();
		}
		try {
			this.ephemera$pacedUploadBody(ci);
		} finally {
			if (!heldLock) {
				this.copyLock.unlock();
			}
		}
	}

	@Unique
	private static int ephemera$headroomLadder(long remNs, int hi, int mid, int low) {
		if (remNs >= 1_200_000L) {
			return hi;
		}
		if (remNs >= 500_000L) {
			return mid;
		}
		if (remNs >= 150_000L) {
			return low;
		}
		return 1;
	}

	@Unique
	private void ephemera$pacedUploadBody(CallbackInfo ci) {
		EphemeraPacerRuntime.beginFrameStreamingBudget();
		FrameBudgetPacer budget = EphemeraPacerRuntime.budget();
		boolean capped = budget.effectiveTargetFps() > 0;
		long remNs = budget.remainingStreamingHeadroomNs(System.nanoTime());
		// Budget enforcement now applies on UNCAPPED frames too: previously an uncapped frame
		// admitted up to 24 sections x 3 layers (2-6 ms of copies) with no headroom check at all
		// (the benchmarks run uncapped — exactly the measured chunk_flight_load /
		// chunk_rebuild_burst 1%-low regime). Ladders are re-derived per layer from live headroom
		// and every non-empty layer keeps a floor of 1 admission, guaranteeing vanilla's
		// render-thread compileSync retry loop always makes progress.
		int remainingBudget = capped
			? ephemera$headroomLadder(remNs, 12, 6, 3)
			: ephemera$headroomLadder(remNs, 10, 5, 2);
		int totalAdmitted = 0;
		// Emergency / watermark drain: when a worker-side append failed (staging full) or the
		// arena's write offset crossed its watermark, bypass per-layer caps entirely so
		// usedBufferCount can reach zero and the bump-pointer staging arena can rotate.
		boolean emergency = StagingFlushState.isEmergencyFlush()
			|| ((StagingBufferUsageAccessor) (Object) this.stagingBuffer).ephemera$nextWriteOffset()
				>= EPHEMERA$STAGING_WATERMARK_BYTES;
		// Cross-layer fairness: a section only swaps its compiled mesh once EVERY layer with
		// staged draws has uploaded, so rotate which layer is admitted first each frame instead
		// of always draining SOLID first (which would pin staging handles on CUTOUT/TRANSLUCENT).
		int layerCount = chunkUberBuffers.size();
		int startOffset = layerCount > 0 ? (int) (budget.frameSequence() % (long) layerCount) : 0;
		int n = 0;
		for (Object raw : chunkUberBuffers.values()) {
			EPHEMERA$ROTATED_LAYERS[n++] = raw;
		}

		GpuDevice device = RenderSystem.getDevice();
		try (StagingBuffer.Uploader uploader = this.stagingBuffer.startUploading(device.createCommandEncoder())) {
			for (int layerIdx = 0; layerIdx < n; layerIdx++) {
				Object raw = EPHEMERA$ROTATED_LAYERS[(layerIdx + startOffset) % n];
				if (!emergency && remainingBudget <= 0) {
					break;
				}
				if (!emergency && totalAdmitted > 0 && budget.remainingStreamingHeadroomNs(System.nanoTime()) <= 0L) {
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
				int layerCap = emergency ? Integer.MAX_VALUE : remainingBudget;
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
					// The index buffer can create its own (32 MiB) heap independently of the
					// vertex buffer's result — count it so heap-creation bursts stay capped at
					// one per frame.
					createdNewHeap |= iBuf.uploadStagedAllocations(device, uploader);
					budget.recordStreamingWork(System.nanoTime() - t0);
				} finally {
					if (deferred) {
						vCtrl.ephemera$restoreDeferred();
						iCtrl.ephemera$restoreDeferred();
						deferredKeys.clear();
					}
				}
				int consumed = Math.max(1, admittedCount);
				totalAdmitted += consumed;
				remainingBudget -= consumed;
				if (createdNewHeap && !emergency) {
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
