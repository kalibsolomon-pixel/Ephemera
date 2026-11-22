package com.ephemera.mixin.client.engine;

import com.ephemera.render.pacing.EphemeraPacerRuntime;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's {@code FramerateLimiter.limitDisplayFPS} with Ephemera's adaptive pacer
 * when Ephemera owns post-swap pacing (or cancels vanilla's wait when Ephemera already paced at
 * frame head). Priority {@code 10500} runs after Aurora Client's {@code 10000}, so if Aurora
 * cancels the call first, Ephemera never interferes.
 */
@Mixin(value = FramerateLimiter.class, priority = 10500)
abstract class FramerateLimiterPacingMixin {
	@Inject(method = "limitDisplayFPS(I)V", at = @At("HEAD"), cancellable = true)
	private static void ephemera$pacePostSwap(int fps, CallbackInfo ci) {
		if (EphemeraPacerRuntime.onPostSwapLimit(fps)) {
			ci.cancel();
		}
	}
}
