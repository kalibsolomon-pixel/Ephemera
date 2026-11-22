package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.StateInterning;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Deduplicates immutable block-local axis coordinate {@link DoubleArrayList}s stored by static
 * {@link ArrayVoxelShape}s, matching and extending FerriteCore's VoxelShape deduplication while
 * skipping ephemeral world-space collision AABBs and {@code OffsetDoubleList}s in O(1) without locking.
 */
@Mixin(ArrayVoxelShape.class)
abstract class ArrayVoxelShapeMixin {
	@Shadow @Final @Mutable private DoubleList xs;
	@Shadow @Final @Mutable private DoubleList ys;
	@Shadow @Final @Mutable private DoubleList zs;

	@Inject(
		method = "<init>(Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape;Lit/unimi/dsi/fastutil/doubles/DoubleList;Lit/unimi/dsi/fastutil/doubles/DoubleList;Lit/unimi/dsi/fastutil/doubles/DoubleList;)V",
		at = @At("RETURN")
	)
	private void ephemera$internCoordLists(DiscreteVoxelShape shape, DoubleList xsIn, DoubleList ysIn, DoubleList zsIn, CallbackInfo ci) {
		if (!EphemeraCommon.config().memoryDeduplicateShapes) {
			return;
		}
		// Reject transient world-space Shapes.create(AABB) / VoxelShape.move() immediately without locking.
		if (ephemera$isBlockLocal(xs)) {
			xs = StateInterning.doubleList(xs);
		}
		if (ephemera$isBlockLocal(ys)) {
			ys = StateInterning.doubleList(ys);
		}
		if (ephemera$isBlockLocal(zs)) {
			zs = StateInterning.doubleList(zs);
		}
	}

	@Unique
	private static boolean ephemera$isBlockLocal(DoubleList list) {
		if (list == null || list.getClass() != DoubleArrayList.class) {
			return false;
		}
		int n = list.size();
		return n > 0 && n <= 16 && list.getDouble(0) >= -1.0 && list.getDouble(n - 1) <= 2.0;
	}
}
