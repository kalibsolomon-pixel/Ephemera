package com.ephemera.mixin.client.engine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reuses a single {@link PoseStack} inside {@link ScreenEffectRenderer#submit} instead of
 * allocating a fresh {@code PoseStack} + backing {@code ArrayList(16)} every frame even when no
 * screen effect is active (measured at ~113 MB / 25s on the Render thread in JFR).
 */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererFastMixin {
	@Unique
	private final PoseStack ephemera$poseStack = new PoseStack();

	@WrapOperation(
		method = "submit",
		at = @At(value = "NEW", target = "()Lcom/mojang/blaze3d/vertex/PoseStack;")
	)
	private PoseStack ephemera$reusePoseStack(Operation<PoseStack> original) {
		PoseStack ps = this.ephemera$poseStack;
		if (ps.isEmpty()) {
			ps.last().pose().identity();
			ps.last().normal().identity();
			return ps;
		}
		return original.call();
	}
}
