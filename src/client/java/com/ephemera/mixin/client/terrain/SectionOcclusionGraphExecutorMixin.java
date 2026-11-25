package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.terrain.ReusableLongSetCopy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Isolates {@link SectionOcclusionGraph}'s async full-graph rebuild onto a dedicated single-thread
 * daemon executor at {@link Thread#NORM_PRIORITY} and true 2-slot double-buffers the
 * {@code emptySections} and {@code loadedChunks} {@link LongOpenHashSet} snapshots via zero-rehash
 * {@link ReusableLongSetCopy} (~88 MB of large {@code long[]} clones eliminated at ~5 us/copy).
 */
@Mixin(SectionOcclusionGraph.class)
abstract class SectionOcclusionGraphExecutorMixin {
	@Unique
	private static final ExecutorService EPHEMERA$OCCLUSION_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Ephemera-Occlusion");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY);
		return t;
	});

	@Unique private final LongOpenHashSet[] ephemera$scratchEmpty = new LongOpenHashSet[] {
		new LongOpenHashSet(256), new LongOpenHashSet(256)
	};
	@Unique private final LongOpenHashSet[] ephemera$scratchLoaded = new LongOpenHashSet[] {
		new LongOpenHashSet(256), new LongOpenHashSet(256)
	};
	@Unique private volatile boolean ephemera$slot0InFlight;
	@Unique private volatile boolean ephemera$slot1InFlight;
	@Unique private int ephemera$activeSlot = -1;

	@Unique
	private int ephemera$acquireFreeSlot() {
		if (!this.ephemera$slot0InFlight) {
			return 0;
		}
		if (!this.ephemera$slot1InFlight) {
			return 1;
		}
		return -1;
	}

	@WrapOperation(
		method = "scheduleFullUpdate",
		at = @At(
			value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;clone()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;",
			ordinal = 0
		)
	)
	private LongOpenHashSet ephemera$reuseEmptySnapshot(LongOpenHashSet source, Operation<LongOpenHashSet> original) {
		this.ephemera$activeSlot = -1;
		if (!EphemeraCommon.config().terrainAsyncGraphUpdates || !ReusableLongSetCopy.isAvailable()) {
			return original.call(source);
		}
		int slot = ephemera$acquireFreeSlot();
		if (slot < 0) {
			return original.call(source);
		}
		LongOpenHashSet dst = this.ephemera$scratchEmpty[slot];
		if (!ReusableLongSetCopy.copyInto(source, dst)) {
			return original.call(source);
		}
		this.ephemera$activeSlot = slot;
		return dst;
	}

	@WrapOperation(
		method = "scheduleFullUpdate",
		at = @At(
			value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;clone()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;",
			ordinal = 1
		)
	)
	private LongOpenHashSet ephemera$reuseLoadedSnapshot(LongOpenHashSet source, Operation<LongOpenHashSet> original) {
		int slot = this.ephemera$activeSlot;
		if (slot < 0 || !EphemeraCommon.config().terrainAsyncGraphUpdates) {
			return original.call(source);
		}
		LongOpenHashSet dst = this.ephemera$scratchLoaded[slot];
		if (!ReusableLongSetCopy.copyInto(source, dst)) {
			return original.call(source);
		}
		return dst;
	}

	@WrapOperation(
		method = "scheduleFullUpdate",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<Void> ephemera$runOnDedicatedOcclusionThread(Runnable task, Executor fallback, Operation<CompletableFuture<Void>> original) {
		int slot = this.ephemera$activeSlot;
		this.ephemera$activeSlot = -1;
		if (!EphemeraCommon.config().terrainAsyncGraphUpdates) {
			return original.call(task, fallback);
		}
		if (slot < 0) {
			return original.call(task, EPHEMERA$OCCLUSION_EXECUTOR);
		}
		if (slot == 0) {
			this.ephemera$slot0InFlight = true;
		} else {
			this.ephemera$slot1InFlight = true;
		}
		Runnable guarded = () -> {
			try {
				task.run();
			} finally {
				if (slot == 0) {
					this.ephemera$slot0InFlight = false;
				} else {
					this.ephemera$slot1InFlight = false;
				}
			}
		};
		return original.call(guarded, EPHEMERA$OCCLUSION_EXECUTOR);
	}
}
