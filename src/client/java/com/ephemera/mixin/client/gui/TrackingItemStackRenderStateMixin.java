package com.ephemera.mixin.client.gui;

import com.ephemera.core.gui.ModelIdentityKey;
import com.ephemera.render.gui.GuiFeatures;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Vanilla returns the raw {@code ArrayList} of model-identity elements as the key that
 * {@code GuiRenderState}, {@code GuiItemAtlas} (via {@code DynamicAtlasAllocator}) and the oversized-item
 * renderers hash on every frame for every item, walking the whole list each time. The state now hands out
 * one {@link ModelIdentityKey} that wraps the same list with identical equality and a cached hash.
 */
@Mixin(TrackingItemStackRenderState.class)
abstract class TrackingItemStackRenderStateMixin {
	@Shadow @Final private List<Object> modelIdentityElements;

	@Unique private ModelIdentityKey ephemera$modelKey;

	@Inject(method = "appendModelIdentityElement", at = @At("TAIL"))
	private void ephemera$invalidateKey(Object element, CallbackInfo ci) {
		if (ephemera$modelKey != null) {
			ephemera$modelKey.elementAppended();
		}
	}

	@Inject(method = "getModelIdentity", at = @At("HEAD"), cancellable = true)
	private void ephemera$cachedKey(CallbackInfoReturnable<Object> cir) {
		if (!GuiFeatures.get().itemStateCache) {
			return;
		}
		ModelIdentityKey key = ephemera$modelKey;
		if (key == null) {
			key = new ModelIdentityKey(modelIdentityElements);
			ephemera$modelKey = key;
		}
		cir.setReturnValue(key);
	}
}
