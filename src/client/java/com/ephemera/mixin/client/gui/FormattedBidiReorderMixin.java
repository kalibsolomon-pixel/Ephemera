package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.FastBidi;
import com.ephemera.render.gui.GuiFeatures;
import net.minecraft.client.resources.language.FormattedBidiReorder;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FormattedBidiReorder.class)
abstract class FormattedBidiReorderMixin {
	@Inject(method = "reorder", at = @At("HEAD"), cancellable = true)
	private static void ephemera$fastLtr(FormattedText text, boolean defaultRtl, CallbackInfoReturnable<FormattedCharSequence> cir) {
		if (GuiFeatures.get().fastLtrText) {
			FormattedCharSequence fast = FastBidi.tryReorder(text, defaultRtl);
			if (fast != null) {
				cir.setReturnValue(fast);
			}
		}
	}
}
