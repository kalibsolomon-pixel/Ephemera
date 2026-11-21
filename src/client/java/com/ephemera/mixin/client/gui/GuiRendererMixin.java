package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiElementOrder;
import com.ephemera.render.gui.GuiFeatures;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Comparator;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
	@ModifyArg(
		method = "prepare",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;sortElements(Ljava/util/Comparator;)V"),
		index = 0
	)
	private Comparator<GuiElementRenderState> ephemera$unboxedElementOrder(Comparator<GuiElementRenderState> vanilla) {
		return GuiFeatures.get().skipTextSortWork ? GuiElementOrder.INSTANCE : vanilla;
	}
}
