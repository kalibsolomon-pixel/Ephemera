package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.UnfocusedFpsController;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void ephemera$unfocusedLimit(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(UnfocusedFpsController.limit(cir.getReturnValue(), minecraft));
	}
}
