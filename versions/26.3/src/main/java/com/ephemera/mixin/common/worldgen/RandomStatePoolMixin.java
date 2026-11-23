package com.ephemera.mixin.common.worldgen;

import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Scales {@link RandomState}'s {@code DensityBufferPool} retention cap and eviction age:
 * <ul>
 *   <li>Scales {@code releaseDensityBufferPool} cap from 16 to {@code max(32, cpus * 2)} so
 *       high-core worker pools do not drop pooled arenas under load;</li>
 *   <li>Increases {@code DensityBufferPool} {@code maxAge} from 20 ticks (1s) to 200 ticks (10s)
 *       so active chunk-flight/worldgen bursts reuse {@code float[]} buffers instead of evicting
 *       and reallocating ~1.5 GB/min.</li>
 * </ul>
 */
@Mixin(RandomState.class)
abstract class RandomStatePoolMixin {
	@Unique
	private static final int EPHEMERA$POOL_CAP = Math.max(32, Runtime.getRuntime().availableProcessors() * 2);
	@Unique
	private static final int EPHEMERA$POOL_MAX_AGE = 200;

	@ModifyConstant(method = "releaseDensityBufferPool", constant = @Constant(intValue = 16))
	private int ephemera$scaleDensityPoolCap(int vanillaCap) {
		return EPHEMERA$POOL_CAP;
	}

	@ModifyConstant(method = "acquireDensityBufferPool", constant = @Constant(intValue = 20))
	private int ephemera$extendDensityBufferMaxAge(int vanillaAge) {
		return EPHEMERA$POOL_MAX_AGE;
	}
}
