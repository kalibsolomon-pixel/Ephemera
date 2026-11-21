package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiFeatures;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Vanilla hard-codes every glyph page to 256x256: constructor allocation, packing root and UV divisor. */
@Mixin(FontTexture.class)
abstract class FontTextureMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 256))
	private int ephemera$pageSize(int vanilla) {
		GuiFeatures features = GuiFeatures.get();
		return features.fontAtlasResizing ? features.fontAtlasSize : vanilla;
	}

	@ModifyConstant(method = "add", constant = @Constant(floatValue = 256.0F))
	private float ephemera$pageSizeUv(float vanilla) {
		GuiFeatures features = GuiFeatures.get();
		return features.fontAtlasResizing ? features.fontAtlasSize : vanilla;
	}
}
