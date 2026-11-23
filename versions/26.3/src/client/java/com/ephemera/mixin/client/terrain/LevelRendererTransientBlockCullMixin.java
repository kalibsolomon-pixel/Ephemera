package com.ephemera.mixin.client.terrain;

import com.ephemera.render.culling.EntityOcclusionManager;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Frustum- and occlusion-culls 26.3 transient blocks inside {@code LevelRenderer#submitTransientBlocks}.
 * Vanilla submits every active transient block (including underground cave gravel/sand collapses and
 * behind-camera blocks whose off-screen sections never recompile before the 1s TTL) directly to
 * {@code MovingBlockFeatureRenderer} every frame, triggering full per-quad AO tessellation on the
 * render thread for completely invisible blocks. Culled blocks remain in {@code transientBlocks}
 * until expiration or section compile so turning the camera toward them renders immediately.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererTransientBlockCullMixin {
	@WrapWithCondition(
		method = "submitTransientBlocks",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitMovingBlock(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V"
		)
	)
	private boolean ephemera$cullInvisibleTransientBlock(SubmitNodeCollector collector, PoseStack poseStack,
														 MovingBlockRenderState state, int outlineColor) {
		return EntityOcclusionManager.shouldSubmitTransientBlock(state.blockPos);
	}
}
