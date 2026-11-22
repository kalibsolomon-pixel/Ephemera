package com.ephemera.mixin.common;

import com.mojang.jtracy.TracyClient;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates cross-thread {@code ACTIVE_COUNT} atomic cache-line bouncing and per-call
 * {@code ThreadLocal.get()} lookups in {@link Profiler#get()} when vanilla wraps frames/ticks
 * in {@link InactiveProfiler#INSTANCE} while neither F3 profiling nor Tracy is active.
 */
@Mixin(Profiler.class)
abstract class ProfilerFastMixin {
	@Shadow @Final private static AtomicInteger ACTIVE_COUNT;

	@Unique private static final Profiler.Scope EPHEMERA$NOOP_SCOPE = () -> {};

	@Inject(method = "use", at = @At("HEAD"), cancellable = true)
	private static void ephemera$fastInactiveUse(ProfilerFiller filler, CallbackInfoReturnable<Profiler.Scope> cir) {
		if (filler == InactiveProfiler.INSTANCE && ACTIVE_COUNT.get() == 0 && !TracyClient.isAvailable()) {
			cir.setReturnValue(EPHEMERA$NOOP_SCOPE);
		}
	}
}
