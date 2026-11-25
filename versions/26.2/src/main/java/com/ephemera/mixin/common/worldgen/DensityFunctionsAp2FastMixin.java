package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.DensityScratchStack;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates ~1.49 GB of temporary {@code double[]} allocations inside
 * {@code DensityFunctions$Ap2.fillArray} (the #1 worker-thread allocator across worldgen) by
 * borrowing depth-indexed ThreadLocal scratch arrays.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
abstract class DensityFunctionsAp2FastMixin {
	@Shadow @Final private DensityFunctions.TwoArgumentSimpleFunction.Type type;
	@Shadow @Final private DensityFunction argument1;
	@Shadow @Final private DensityFunction argument2;

	/**
	 * @author Ephemera
	 * @reason Reuses ThreadLocal depth-indexed scratch arrays for ADD instead of allocating new double[] every cell.
	 */
	@Overwrite
	public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
		this.argument1.fillArray(array, contextProvider);
		switch (this.type) {
			case ADD -> {
				double[] tmp = DensityScratchStack.borrow(array.length);
				try {
					this.argument2.fillArray(tmp, contextProvider);
					for (int i = 0, n = array.length; i < n; i++) {
						array[i] += tmp[i];
					}
				} finally {
					DensityScratchStack.release();
				}
			}
			case MUL -> {
				for (int i = 0, n = array.length; i < n; i++) {
					double d = array[i];
					array[i] = d == 0.0 ? 0.0 : d * this.argument2.compute(contextProvider.forIndex(i));
				}
			}
			case MIN -> {
				double min2 = this.argument2.minValue();
				for (int i = 0, n = array.length; i < n; i++) {
					double d = array[i];
					array[i] = d < min2 ? d : Math.min(d, this.argument2.compute(contextProvider.forIndex(i)));
				}
			}
			case MAX -> {
				double max2 = this.argument2.maxValue();
				for (int i = 0, n = array.length; i < n; i++) {
					double d = array[i];
					array[i] = d > max2 ? d : Math.max(d, this.argument2.compute(contextProvider.forIndex(i)));
				}
			}
		}
	}
}
