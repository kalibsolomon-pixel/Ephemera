package com.ephemera.mixin.client.item;

import com.ephemera.render.fastwrite.CachedItemQuadList;
import java.util.List;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers {@link ItemFeatureRenderer.Submit#hasTranslucency()} in O(1) without allocating an
 * iterator or walking every {@link BakedQuad} when backed by a {@link CachedItemQuadList}.
 */
@Mixin(ItemFeatureRenderer.Submit.class)
public abstract class ItemSubmitFastTranslucencyMixin {
	@Shadow @Final private List<BakedQuad> quads;

	@Inject(method = "hasTranslucency", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastHasTranslucency(CallbackInfoReturnable<Boolean> cir) {
		if (this.quads instanceof CachedItemQuadList cached) {
			cir.setReturnValue(cached.hasTranslucency);
		}
	}
}
