package com.ephemera.mixin.common.worldgen;

import com.ephemera.core.math.FastNoiseMath;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Bit-exact fast path for {@link ImprovedNoise}: specializes the common 3-arg {@code yScale == 0}
 * call and replaces 2D {@code SimplexNoise.GRADIENT[hash & 15]} array lookups and int-to-double
 * multiplies with {@link FastNoiseMath#gradDot}.
 */
@Mixin(ImprovedNoise.class)
abstract class ImprovedNoiseFastMixin {
	@Shadow @Final private byte[] p;
	@Shadow @Final public double xo;
	@Shadow @Final public double yo;
	@Shadow @Final public double zo;

	/**
	 * @author Ephemera
	 * @reason Specialized yScale==0 path with hoisted permutation table and branch-table gradDot.
	 */
	@Overwrite
	public double noise(double x, double y, double z) {
		return FastNoiseMath.sampleImprovedNoYScale(this.p, x + this.xo, y + this.yo, z + this.zo);
	}

	/**
	 * @author Ephemera
	 * @reason Hoisted permutation table and branch-table gradDot with bit-exact yScale handling.
	 */
	@Overwrite
	public double noise(double x, double y, double z, double yScale, double yMax) {
		return FastNoiseMath.sampleImproved(this.p, x + this.xo, y + this.yo, z + this.zo, yScale, yMax);
	}

	/**
	 * @author Ephemera
	 * @reason Branch-table gradDot also accelerates sampleWithDerivative.
	 */
	@Overwrite
	public static double gradDot(int hash, double x, double y, double z) {
		return FastNoiseMath.gradDot(hash, x, y, z);
	}
}
