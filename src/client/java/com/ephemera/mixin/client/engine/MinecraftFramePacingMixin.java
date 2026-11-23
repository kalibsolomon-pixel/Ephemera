package com.ephemera.mixin.client.engine;

import com.ephemera.render.pacing.EphemeraPacerRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coordinates Ephemera's frame-head pacer and per-frame CPU work budget measurement.
 * Paired Aurora clients provide their FPS cap before frame hooks read it.
 */
@Mixin(value = Minecraft.class, priority = 10500)
abstract class MinecraftFramePacingMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void ephemera$onFrameHead(boolean advanceGameTime, CallbackInfo ci) {
		EphemeraPacerRuntime.onFrameHead((Minecraft) (Object) this);
	}

	@Inject(method = "renderFrame", at = @At("RETURN"))
	private void ephemera$onRenderFrameEnd(boolean advanceGameTime, CallbackInfo ci) {
		EphemeraPacerRuntime.onRenderFrameEnd();
	}
}
