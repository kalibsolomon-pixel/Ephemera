package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's occlusion-graph ray march (the {@code >60}-block visibility walk over each face of
 * every propagated section, run on the render thread for partial updates and on the occlusion
 * executor for async full rebuilds) allocates two {@code Vector3d}s per face and a fresh
 * {@code BlockPos} per march step. All three are fully initialized at construction, mutated only
 * locally, and never escape the method — so thread-local scratch instances are drop-in identical
 * while removing the bulk of the graph update's GC churn. Runs on both the render thread and the
 * occlusion executor, hence the per-thread scratch.
 */
@Mixin(net.minecraft.client.renderer.SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphRayMarchMixin {
	@Unique
	private static final ThreadLocal<Vector3d[]> EPHEMERA$RAY_SCRATCH = ThreadLocal.withInitial(
		() -> new Vector3d[] {new Vector3d(), new Vector3d()});
	@Unique
	private static final ThreadLocal<BlockPos.MutableBlockPos> EPHEMERA$MARCH_POS = ThreadLocal.withInitial(
		BlockPos.MutableBlockPos::new);


	@WrapOperation(
		method = "runUpdates",
		at = @At(value = "NEW", target = "(DDD)Lorg/joml/Vector3d;", ordinal = 0)
	)
	private static Vector3d ephemera$marchCorner(double x, double y, double z, Operation<Vector3d> original) {
		if (!EphemeraCommon.config().terrainFastFrustumAndGrid) {
			return original.call(x, y, z);
		}
		Vector3d corner = EPHEMERA$RAY_SCRATCH.get()[0];
		corner.set(x, y, z);
		return corner;
	}

	@WrapOperation(
		method = "runUpdates",
		at = @At(value = "NEW", target = "(DDD)Lorg/joml/Vector3d;", ordinal = 1)
	)
	private static Vector3d ephemera$marchRay(double x, double y, double z, Operation<Vector3d> original) {
		if (!EphemeraCommon.config().terrainFastFrustumAndGrid) {
			return original.call(x, y, z);
		}
		Vector3d ray = EPHEMERA$RAY_SCRATCH.get()[1];
		ray.set(x, y, z);
		return ray;
	}

	@WrapOperation(
		method = "runUpdates",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"
		)
	)
	private static BlockPos ephemera$marchPos(double x, double y, double z, Operation<BlockPos> original) {
		if (!EphemeraCommon.config().terrainFastFrustumAndGrid) {
			return original.call(x, y, z);
		}
		return EPHEMERA$MARCH_POS.get().set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
	}

}
