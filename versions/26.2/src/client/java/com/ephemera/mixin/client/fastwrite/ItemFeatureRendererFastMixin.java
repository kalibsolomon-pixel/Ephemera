package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastQuadWriter;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Batches contiguous {@link RenderType} quad runs in {@link ItemFeatureRenderer#prepareMainSubmit}
 * and {@link ItemFeatureRenderer#prepareFoilSubmit} so held, dropped, and GUI items resolve their
 * {@code VertexConsumer} / foil decal wrapper ONCE per material run instead of once per quad
 * (up to 200x fewer HashMap lookups and SheetedDecal allocations per item), while writing vertices
 * in a single {@link FastQuadWriter} reservation.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererFastMixin extends RenderTypeFeatureRenderer<ItemFeatureRenderer.Submit> {
	@Shadow @Final private QuadInstance quadInstance;
	@Shadow private static int getLayerColorSafe(int[] tintLayers, BakedQuad.MaterialInfo info) { throw new AssertionError(); }
	@Shadow private static PoseStack.Pose computeFoilDecalPose(ItemDisplayContext context, PoseStack.Pose pose) { throw new AssertionError(); }
	@Shadow private VertexConsumer getFoilBuffer(RenderType itemRenderType, PoseStack.Pose foilPose) { throw new AssertionError(); }

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
		PoseStack.Pose pose = submit.pose();
		int[] tintLayers = submit.tintLayers();
		boolean trackSprites = VisibleSpriteTracker.trackingFrame();

		int i = 0;
		while (i < size) {
			RenderType type = quads.get(i).materialInfo().itemRenderType();
			int runEnd = i + 1;
			while (runEnd < size && quads.get(runEnd).materialInfo().itemRenderType() == type) {
				runEnd++;
			}
			VertexConsumer consumer = this.getVertexBuilder(type);
			if (!FastQuadWriter.writeItemRun(consumer, pose, quads, i, runEnd, tintLayers, lightCoords, overlayCoords, trackSprites)) {
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
		// Keep quadInstance.color consistent with vanilla's final quad for any subsequent foil pass.
		this.quadInstance.setColor(getLayerColorSafe(tintLayers, quads.get(size - 1).materialInfo()));
		ci.cancel();
	}

	@Inject(method = "prepareFoilSubmit", at = @At("HEAD"), cancellable = true)
	private void ephemera$batchFoilSubmit(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		ItemStackRenderState.FoilType foilType = submit.foilType();
		if (foilType == ItemStackRenderState.FoilType.NONE) {
			ci.cancel();
			return;
		}
		List<BakedQuad> quads = submit.quads();
		int size = quads.size();
		if (size == 0) {
			ci.cancel();
			return;
		}
		PoseStack.Pose pose = submit.pose();
		PoseStack.Pose foilPose = foilType == ItemStackRenderState.FoilType.SPECIAL
			? computeFoilDecalPose(submit.displayContext(), pose)
			: null;

		int i = 0;
		while (i < size) {
			RenderType type = quads.get(i).materialInfo().itemRenderType();
			int runEnd = i + 1;
			while (runEnd < size && quads.get(runEnd).materialInfo().itemRenderType() == type) {
				runEnd++;
			}
			VertexConsumer foilConsumer = this.getFoilBuffer(type, foilPose);
			if (!FastQuadWriter.writeFoilRun(foilConsumer, pose, quads, i, runEnd, this.quadInstance)) {
				for (int j = i; j < runEnd; j++) {
					foilConsumer.putBakedQuad(pose, quads.get(j), this.quadInstance);
				}
			}
			i = runEnd;
		}
		ci.cancel();
	}
}
