package com.ephemera.mixin.client.engine;

import com.ephemera.render.pacing.EphemeraPacerRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coordinates Ephemera's frame-head pacer and per-frame CPU work budget measurement.
 *
 * <p>Uses priority {@code 10500} (after Aurora Client's {@code 10000}) so when both mods are
 * installed, Aurora's frame-head hook reports ownership first and Ephemera seamlessly yields the
 * blocking wait while syncing its adaptive terrain-upload budget to Aurora's exact target FPS.
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
