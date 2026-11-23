package com.ephemera.mixin.common.worldgen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces unpooled per-chunk {@link DensityBuffer#createUnpooled(int)} allocations inside
 * {@link MaterialRuleContext} (~819 MB / 26s during chunk flight) with a per-thread
 * {@link DensityBufferPool}. {@code MaterialRuleContext} is instantiated exclusively inside
 * {@code MaterialSystem#buildSurface} and never escapes that single-threaded chunk call; any
 * buffers acquired for the previous chunk on the same worker thread are returned to the thread's
 * pool at the start of the next {@code MaterialRuleContext} construction.
 */
@Mixin(MaterialRuleContext.class)
abstract class MaterialRuleContextPoolMixin {
	@Unique
	private static final int EPHEMERA$MAX_POOLED_FLOATS = 131_072;

	@Unique
	private static final ThreadLocal<DensityBufferPool> EPHEMERA$THREAD_POOL =
		ThreadLocal.withInitial(() -> new DensityBufferPool(64));

	@Unique
	private static final ThreadLocal<ArrayList<ScopedDensityBuffer>> EPHEMERA$THREAD_ACTIVE =
		ThreadLocal.withInitial(() -> new ArrayList<>(4));

	@Inject(method = "<init>", at = @At("HEAD"))
	private static void ephemera$recyclePreviousSurfaceBuffers(CallbackInfo ci) {
		ArrayList<ScopedDensityBuffer> active = EPHEMERA$THREAD_ACTIVE.get();
		for (int i = active.size() - 1; i >= 0; i--) {
			active.get(i).close();
		}
		active.clear();
	}

	@WrapOperation(
		method = {"getDensitiesInChunk", "getMinSurfaceLevel"},
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;createUnpooled(I)Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;"
		)
	)
	private DensityBuffer ephemera$acquirePooledSurfaceBuffer(int size, Operation<DensityBuffer> original) {
		if (size <= 0 || size > EPHEMERA$MAX_POOLED_FLOATS) {
			return original.call(size);
		}
		ArrayList<ScopedDensityBuffer> active = EPHEMERA$THREAD_ACTIVE.get();
		if (active.size() >= 8) {
			return original.call(size);
		}
		ScopedDensityBuffer buf = EPHEMERA$THREAD_POOL.get().acquire(size);
		active.add(buf);
		return buf;
	}
}
