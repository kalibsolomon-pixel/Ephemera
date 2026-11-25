package com.ephemera.mixin.common;

import com.ephemera.engine.common.BlockTraversalSetPool;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuses a ThreadLocal {@link LongOpenHashSet} inside
 * {@link BlockGetter#forEachBlockIntersectedBetween} instead of allocating a fresh hash set and
 * backing {@code long[]} on every moving entity tick (~84 MB on Server thread).
 */
@Mixin(BlockGetter.class)
public interface BlockGetterFastMixin {
	@Inject(method = "forEachBlockIntersectedBetween", at = @At("HEAD"))
	private static void ephemera$enterTraversal(
		Vec3 from, Vec3 to, AABB box, BlockGetter.BlockStepVisitor visitor,
		CallbackInfoReturnable<Boolean> cir
	) {
		BlockTraversalSetPool.enterFrame();
	}

	@WrapOperation(
		method = "forEachBlockIntersectedBetween",
		at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;")
	)
	private static LongOpenHashSet ephemera$borrowVisitedSet(Operation<LongOpenHashSet> original) {
		return BlockTraversalSetPool.borrow();
	}

	@Inject(method = "forEachBlockIntersectedBetween", at = @At("RETURN"))
	private static void ephemera$exitTraversal(
		Vec3 from, Vec3 to, AABB box, BlockGetter.BlockStepVisitor visitor,
		CallbackInfoReturnable<Boolean> cir
	) {
		BlockTraversalSetPool.exitFrame();
	}
}
