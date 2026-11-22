package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.UnfocusedFpsController;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private long latestInputTime;

	// Vanilla leaves latestInputTime at 0 while Util.getMillis() is system uptime, so the AFK
	// 10/30 fps throttle can engage on launch before the first input. Seed it with "now".
	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$seedLatestInputTime(CallbackInfo ci) {
		this.latestInputTime = Util.getMillis();
	}

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void ephemera$unfocusedLimit(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(UnfocusedFpsController.limit(cir.getReturnValue(), minecraft));
	}
}
