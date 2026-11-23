package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastQuadWriter;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batches contiguous {@link RenderType} quad runs in 26.3's unified
 * {@link ItemFeatureRenderer#prepareMainSubmit} (covering {@code NONE}, {@code STANDARD}, and
 * {@code SPECIAL} foil types) so held, dropped, and GUI items resolve their
 * {@code VertexConsumer} ONCE per material run instead of once per quad, while writing standard
 * runs in a single {@link FastQuadWriter#writeItemRun} reservation.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererFastMixin extends RenderTypeFeatureRenderer<ItemFeatureRenderer.Submit> {
	@Shadow @Final private QuadInstance quadInstance;
	@Shadow private static int getLayerColorSafe(int[] tintLayers, BakedQuad.MaterialInfo info) { throw new AssertionError(); }
	@Shadow private static PoseStack.Pose computeFoilDecalPose(ItemDisplayContext context, PoseStack.Pose pose) { throw new AssertionError(); }

	@Unique
	private static RenderType ephemera$resolveRenderType(BakedQuad.MaterialInfo info, ItemStackRenderState.FoilType foilType) {
		return switch (foilType) {
			case NONE -> info.itemRenderType();
			case STANDARD -> info.itemGlintRenderType();
			case SPECIAL -> info.itemGlintSpecialRenderType();
		};
	}

	@Inject(method = "prepareMainSubmit", at = @At("HEAD"), cancellable = true)
	private void ephemera$batchMainSubmit(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		List<BakedQuad> quads = submit.quads();
		int size = quads.size();
		if (size == 0) {
			ci.cancel();
			return;
		}
		int lightCoords = submit.lightCoords();
		int overlayCoords = submit.overlayCoords();
		this.quadInstance.setLightCoords(lightCoords);
		this.quadInstance.setOverlayCoords(overlayCoords);
		ItemStackRenderState.FoilType foilType = submit.foilType();
		PoseStack.Pose pose = submit.pose();
		PoseStack.Pose foilPose = foilType == ItemStackRenderState.FoilType.SPECIAL
			? computeFoilDecalPose(submit.displayContext(), pose)
			: null;
		int[] tintLayers = submit.tintLayers();
		boolean trackSprites = VisibleSpriteTracker.trackingFrame();

		int i = 0;
		while (i < size) {
			RenderType type = ephemera$resolveRenderType(quads.get(i).materialInfo(), foilType);
			int runEnd = i + 1;
			while (runEnd < size && ephemera$resolveRenderType(quads.get(runEnd).materialInfo(), foilType) == type) {
				runEnd++;
			}
			VertexConsumer consumer = this.getVertexBuilder(type);
			if (foilType == ItemStackRenderState.FoilType.SPECIAL) {
				for (int j = i; j < runEnd; j++) {
					BakedQuad quad = quads.get(j);
					if (trackSprites) {
						VisibleSpriteTracker.markExternal(quad);
					}
					this.quadInstance.setColor(getLayerColorSafe(tintLayers, quad.materialInfo()));
					consumer.putBakedQuadWithGlint(pose, quad, this.quadInstance, foilPose);
				}
			} else if (!FastQuadWriter.writeItemRun(consumer, pose, quads, i, runEnd, tintLayers, lightCoords, overlayCoords, trackSprites)) {
				for (int j = i; j < runEnd; j++) {
					BakedQuad quad = quads.get(j);
					if (trackSprites) {
						VisibleSpriteTracker.markExternal(quad);
					}
					this.quadInstance.setColor(getLayerColorSafe(tintLayers, quad.materialInfo()));
					consumer.putBakedQuad(pose, quad, this.quadInstance);
				}
			}
			i = runEnd;
		}
		this.quadInstance.setColor(getLayerColorSafe(tintLayers, quads.get(size - 1).materialInfo()));
		ci.cancel();
	}
}
