package com.ephemera.mixin.common.worldgen;

import com.ephemera.core.math.FastNoiseMath;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces {@code GRADIENT[hash & 15]} record lookup and {@code int -> float} conversions in 26.3
 * {@link GradientNoise#gradDot(int, float, float, float)} with L1-resident flat float tables.
 */
@Mixin(GradientNoise.class)
abstract class GradientNoiseFastMixin {
	/**
	 * @author Ephemera
	 * @reason Flat primitive float gradient tables avoid object array dereference and int-to-float casts.
	 */
	@Overwrite
	protected static float gradDot(int hash, float x, float y, float z) {
		return FastNoiseMath.gradDotF(hash, x, y, z);
	}
}
