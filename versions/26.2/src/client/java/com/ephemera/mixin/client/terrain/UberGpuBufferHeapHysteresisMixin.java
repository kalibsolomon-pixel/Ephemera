package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.IdentityHashMap;

/**
 * Vanilla's upload pass closes any fully-free {@code UberGpuBufferHeap} at the end of every call;
 * during flight (sections freed behind the camera, new ones ahead) this create/destroys
 * multi-megabyte-to-128 MiB driver allocations every few frames — each creation is a synchronous
 * multi-millisecond render-thread spike. A heap that has just become completely free is retained
 * for a cooldown before vanilla is allowed to close it, so flight churn stops thrashing heaps
 * while genuinely idle buffers still release after two seconds.
 */
@Mixin(UberGpuBuffer.class)
public abstract class UberGpuBufferHeapHysteresisMixin {
	@Unique
	private static final IdentityHashMap<TlsfAllocator, Long> EPHEMERA$FREE_SINCE_NS = new IdentityHashMap<>();

	@Unique
	private static final long EPHEMERA$HEAP_RETENTION_NS = 2_000_000_000L;

	@WrapOperation(
		method = "uploadStagedAllocations",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/TlsfAllocator;isCompletelyFree()Z")
	)
	private boolean ephemera$heapRetentionHysteresis(TlsfAllocator allocator, Operation<Boolean> original) {
		boolean completelyFree = original.call(allocator);
		if (!EphemeraCommon.config().framePacerAdaptiveUploadBudget) {
			return completelyFree;
		}
		if (!completelyFree) {
			EPHEMERA$FREE_SINCE_NS.remove(allocator);
			return false;
		}
		// Render-thread confined (both upload call sites assert it), so the identity map is safe.
		long now = System.nanoTime();
		Long freeSince = EPHEMERA$FREE_SINCE_NS.get(allocator);
		if (freeSince == null) {
			EPHEMERA$FREE_SINCE_NS.put(allocator, now);
			return false;
		}
		return now - freeSince >= EPHEMERA$HEAP_RETENTION_NS;
	}
}
