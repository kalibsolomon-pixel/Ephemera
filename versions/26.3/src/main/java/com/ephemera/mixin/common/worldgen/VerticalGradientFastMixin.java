package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.XoroshiroPositionalFastAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates ~775 MB / 60s of {@code XoroshiroRandomSource} + {@code Xoroshiro128PlusPlus}
 * allocations inside 26.3 {@code VerticalGradientCondition$1#compute} by drawing the single
 * gradient float directly via {@link XoroshiroPositionalFastAccess}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition$1")
abstract class VerticalGradientFastMixin extends MaterialRuleContext.LazyYCondition {
	@Shadow @Final int val$trueAtAndBelow;
	@Shadow @Final int val$falseAtAndAbove;
	@Shadow @Final PositionalRandomFactory val$randomFactory;

	protected VerticalGradientFastMixin(MaterialRuleContext context) {
		super(context);
	}

	/**
	 * @author Ephemera
	 * @reason Direct Xoroshiro single-float evaluation avoids allocating two RNG objects per gradient block test.
	 */
	@Overwrite
	@Override
	protected boolean compute() {
		int y = this.context.blockY();
		if (y <= this.val$trueAtAndBelow) {
			return true;
		}
		if (y >= this.val$falseAtAndAbove) {
			return false;
		}
		double threshold = Mth.map((double) y, (double) this.val$trueAtAndBelow, (double) this.val$falseAtAndAbove, 1.0, 0.0);
		PositionalRandomFactory factory = this.val$randomFactory;
		float roll = factory instanceof XoroshiroPositionalFastAccess fast
			? fast.ephemera$nextFloatAt(this.context.blockX(), y, this.context.blockZ())
			: factory.at(this.context.blockX(), y, this.context.blockZ()).nextFloat();
		return (double) roll < threshold;
	}
}
