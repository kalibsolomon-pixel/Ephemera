package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiItemBakingManager;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
abstract class TextureManagerMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void ephemera$countTextureTick(CallbackInfo ci) {
		GuiItemBakingManager.onTextureTick();
	}
}
