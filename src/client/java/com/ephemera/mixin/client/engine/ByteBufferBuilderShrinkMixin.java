package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.memory.MemoryPressurePolicy;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.jtracy.MemoryPool;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents silent off-heap native memory leaks in {@link ByteBufferBuilder}.
 *
 * <p>Vanilla grows a builder's native buffer via {@code realloc} on peak chunk/entity/GUI frames
 * (often 16–64 MiB per worker builder) and never shrinks it back down. After sustained low
 * utilization, this mixin safely reallocates the empty buffer back to a modest power-of-two size,
 * keeping native RSS flat over multi-hour sessions.
 */
@Mixin(ByteBufferBuilder.class)
abstract class ByteBufferBuilderShrinkMixin {
	@Shadow @Final private static MemoryPool MEMORY_POOL;
	@Shadow @Final private static MemoryUtil.MemoryAllocator ALLOCATOR;

	@Shadow private long pointer;
	@Shadow private long capacity;
	@Shadow private long writeOffset;
	@Shadow private long nextResultOffset;
	@Shadow private int resultCount;

	@Unique private long ephemera$initialCapacity;
	@Unique private long ephemera$cyclePeakBytes;
	@Unique private MemoryPressurePolicy.NativeBufferShrinkTracker ephemera$shrinkTracker;

	@Inject(method = "<init>(IJ)V", at = @At("RETURN"))
	private void ephemera$captureInitialCapacity(int initialCapacity, long maxCapacity, CallbackInfo ci) {
		ephemera$initialCapacity = Math.max(4096L, initialCapacity);
	}

	@Inject(method = "discardResults", at = @At("HEAD"))
	private void ephemera$noteCycleUsage(CallbackInfo ci) {
		if (writeOffset > ephemera$cyclePeakBytes) {
			ephemera$cyclePeakBytes = writeOffset;
		}
	}

	@Inject(method = "discardResults", at = @At("RETURN"))
	private void ephemera$maybeShrinkOffHeap(CallbackInfo ci) {
		if (pointer == 0L || resultCount != 0 || writeOffset != 0L || nextResultOffset != 0L) {
			return;
		}
		long peak = ephemera$cyclePeakBytes;
		ephemera$cyclePeakBytes = 0L;
		if (!EphemeraCommon.config().memoryTrimNativeBuffers
			|| capacity <= MemoryPressurePolicy.DEFAULT_NATIVE_SHRINK_THRESHOLD_BYTES) {
			return;
		}
		MemoryPressurePolicy.NativeBufferShrinkTracker tracker = ephemera$shrinkTracker;
		if (tracker == null) {
			long baseline = Math.max(256L * 1024L, ephemera$initialCapacity);
			tracker = new MemoryPressurePolicy.NativeBufferShrinkTracker(
				baseline,
				MemoryPressurePolicy.DEFAULT_NATIVE_SHRINK_THRESHOLD_BYTES,
				MemoryPressurePolicy.DEFAULT_IDLE_CLEARS_BEFORE_SHRINK
			);
			ephemera$shrinkTracker = tracker;
		}
		long target = tracker.onClear(capacity, peak);
		if (target > 0L && target < capacity) {
			long oldPtr = pointer;
			long oldCap = capacity;
			MEMORY_POOL.free(oldPtr);
			long newPtr = ALLOCATOR.realloc(oldPtr, target);
			if (newPtr != 0L) {
				pointer = newPtr;
				capacity = target;
				MEMORY_POOL.malloc(newPtr, (int) Math.min(target, Integer.MAX_VALUE));
				MemoryPressurePolicy.recordNativeReclaimed(oldCap - target);
			} else {
				// Defensive restore if allocator ever refuses shrink: old pointer remains valid per C realloc semantics.
				MEMORY_POOL.malloc(oldPtr, (int) Math.min(oldCap, Integer.MAX_VALUE));
			}
		}
	}
}
