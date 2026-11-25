package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.XoroshiroPositionalFastAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates ~426 MB of {@code XoroshiroRandomSource} + {@code Xoroshiro128PlusPlus} +
 * {@code MarsagliaPolarGaussian} allocations inside 26.2
 * {@code SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition#compute}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition")
abstract class VerticalGradientFastMixin extends SurfaceRules.LazyYCondition {
	@Shadow @Final int val$trueAtAndBelow;
	@Shadow @Final int val$falseAtAndAbove;
	@Shadow @Final PositionalRandomFactory val$randomFactory;

	protected VerticalGradientFastMixin(SurfaceRules.Context context) {
		super(context);
	}

	/**
	 * @author Ephemera
	 * @reason Direct Xoroshiro single-float evaluation avoids allocating three RNG objects per gradient block test.
	 */
	@Overwrite
	@Override
	protected boolean compute() {
		int y = this.context.blockY;
		if (y <= this.val$trueAtAndBelow) {
			return true;
		}
		if (y >= this.val$falseAtAndAbove) {
			return false;
		}
		double threshold = Mth.map((double) y, (double) this.val$trueAtAndBelow, (double) this.val$falseAtAndAbove, 1.0, 0.0);
		PositionalRandomFactory factory = this.val$randomFactory;
		float roll = factory instanceof XoroshiroPositionalFastAccess fast
			? fast.ephemera$nextFloatAt(this.context.blockX, y, this.context.blockZ)
			: factory.at(this.context.blockX, y, this.context.blockZ).nextFloat();
		return (double) roll < threshold;
	}
}
