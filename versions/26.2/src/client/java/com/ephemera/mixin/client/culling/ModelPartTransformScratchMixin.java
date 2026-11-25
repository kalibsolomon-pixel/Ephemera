package com.ephemera.mixin.client.culling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eliminates the per-animated-part {@code new Quaternionf()} allocation in
 * {@link ModelPart#translateAndRotate} (dense mob scenes: hundreds of allocations per frame —
 * pure render-thread GC churn) via a render-thread scratch instance, mirroring the proven
 * {@code GlyphRenderStateFastMixin} / {@code ParticleCullingManager.RENDER_ROTATION} patterns.
 *
 * <p>Safe because vanilla immediately overwrites all four components with
 * {@code rotationZYX(...)}, passes it to {@code PoseStack.mulPose} which only reads it, and the
 * reference never escapes {@code translateAndRotate}. Off the render thread (and under
 * transform-hook conflicts) vanilla allocation is preserved.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartTransformScratchMixin {
	@Unique
	private static final ThreadLocal<Quaternionf> EPHEMERA$SCRATCH = ThreadLocal.withInitial(Quaternionf::new);

	@WrapOperation(
		method = "translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;")
	)
	private Quaternionf ephemera$reuseRotationScratch(Operation<Quaternionf> original) {
		if (RenderSystem.isOnRenderThread()) {
			// rotationZYX below overwrites all four components, so the scratch
			// needs no clearing here.
			return EPHEMERA$SCRATCH.get();
		}
		return original.call();
	}
}
