package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.gui.GuiLayerIndex;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(GuiRenderState.class)
abstract class GuiRenderStateMixin {
	@Unique private final GuiLayerIndex ephemera$layerIndex = new GuiLayerIndex();

	@Inject(method = "hasIntersection", at = @At("HEAD"), cancellable = true)
	private void ephemera$indexedIntersection(ScreenRectangle bounds, List<? extends ScreenArea> areas, CallbackInfoReturnable<Boolean> cir) {
		if (GuiFeatures.get().layerIndexing) {
			cir.setReturnValue(ephemera$layerIndex.intersects(bounds, areas));
		}
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void ephemera$resetLayerIndex(CallbackInfo ci) {
		ephemera$layerIndex.clear();
	}
}
