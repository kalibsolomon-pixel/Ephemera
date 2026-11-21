package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.ExternalSpriteUseHook;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Stamps animated sprites of blocks moved by pistons. */
@Mixin(MovingBlockFeatureRenderer.class)
public abstract class TerrainMovingBlockFeatureSpriteUseMixin implements ExternalSpriteUseHook {
	@ModifyArg(
		method = "putBakedQuad",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),
		index = 1
	)
	private BakedQuad ephemera$markMovingBlockSprite(BakedQuad quad) {
		VisibleSpriteTracker.markExternal(quad);
		return quad;
	}
}
