package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.ExternalSpriteUseHook;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Stamps animated sprites of item quads (held, dropped, GUI, item frames) as in use. */
@Mixin(ItemFeatureRenderer.class)
public abstract class TerrainItemFeatureSpriteUseMixin implements ExternalSpriteUseHook {
	@ModifyArg(
		method = "prepareMainSubmit",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),
		index = 1
	)
	private BakedQuad ephemera$markItemSprite(BakedQuad quad) {
		VisibleSpriteTracker.markExternal(quad);
		return quad;
	}
}
