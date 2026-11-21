package com.ephemera.mixin.common;

import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Prevents background {@code Worker-Main} ForkJoin threads from oversubscribing all CPU cores
 * and preempting the Render Thread or Integrated Server Thread during mouse movement, chunk
 * streaming, and frontier worldgen.
 *
 * <ul>
 *   <li>Lowers background ForkJoin worker priority to {@code NORM_PRIORITY - 1} (4) so the OS
 *       scheduler keeps Render (6) and Server (5) threads on performance cores.</li>
 *   <li>On CPUs with &gt;= 8 hardware threads (unless {@code -Dmax.bg.threads} is explicitly set),
 *       reserves headroom for Render, Server, Occlusion, AsyncPresent, and GPU driver threads.</li>
 * </ul>
 */
@Mixin(Util.class)
abstract class UtilWorkerPriorityMixin {
	@Redirect(
		method = "makeExecutor",
		at = @At(
			value = "NEW",
			target = "(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)Ljava/util/concurrent/ForkJoinPool;"
		)
	)
	private static ForkJoinPool ephemera$createLowerPriorityPool(
		int parallelism,
		ForkJoinPool.ForkJoinWorkerThreadFactory factory,
		Thread.UncaughtExceptionHandler handler,
		boolean asyncMode
	) {
		ForkJoinPool.ForkJoinWorkerThreadFactory wrapped = pool -> {
			ForkJoinWorkerThread thread = factory.newThread(pool);
			if (thread != null) {
				try {
					thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
				} catch (SecurityException ignored) {
				}
			}
			return thread;
		};
		return new ForkJoinPool(parallelism, wrapped, handler, asyncMode);
	}

	@Inject(method = "maxAllowedExecutorThreads", at = @At("RETURN"), cancellable = true)
	private static void ephemera$reserveForegroundCores(CallbackInfoReturnable<Integer> cir) {
		if (System.getProperty("max.bg.threads") != null) {
			return;
		}
		int cpus = Runtime.getRuntime().availableProcessors();
		if (cpus < 8) {
			return;
		}
		int reserve = Math.max(2, cpus / 4);
		int target = Math.max(4, cpus - reserve);
		if (cir.getReturnValueI() > target) {
			cir.setReturnValue(target);
		}
	}
}
