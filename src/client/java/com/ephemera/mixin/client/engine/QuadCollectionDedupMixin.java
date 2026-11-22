package com.ephemera.mixin.client.engine;

import com.ephemera.engine.client.ModelInterning;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Deduplicates {@link BakedQuad}, {@link BakedQuad.MaterialInfo}, and vertex position vectors as
 * quads are added to {@link QuadCollection.Builder} during resource-pack model baking.
 */
@Mixin(QuadCollection.Builder.class)
abstract class QuadCollectionDedupMixin {
	@ModifyVariable(method = "addCulledFace", at = @At("HEAD"), argsOnly = true)
	private BakedQuad ephemera$dedupCulledQuad(BakedQuad quad) {
		return ModelInterning.internQuad(quad);
	}

	@ModifyVariable(method = "addUnculledFace", at = @At("HEAD"), argsOnly = true)
	private BakedQuad ephemera$dedupUnculledQuad(BakedQuad quad) {
		return ModelInterning.internQuad(quad);
	}
}
