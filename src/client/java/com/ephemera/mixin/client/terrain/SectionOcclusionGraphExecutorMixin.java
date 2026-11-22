package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
 * daemon executor at {@link Thread#NORM_PRIORITY} so fast mouse whip-pans and chunk-border crossings
 * never wait behind queued worldgen tasks on {@code Util.backgroundExecutor()}.
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

	@WrapOperation(
		method = "scheduleFullUpdate",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<Void> ephemera$runOnDedicatedOcclusionThread(Runnable task, Executor fallback, Operation<CompletableFuture<Void>> original) {
		Executor exec = EphemeraCommon.config().terrainAsyncGraphUpdates ? EPHEMERA$OCCLUSION_EXECUTOR : fallback;
		return original.call(task, exec);
	}
}
