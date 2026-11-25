package com.ephemera.mixin.client.engine;

import com.ephemera.render.pacing.EphemeraPacerRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage 2 pre-present cadence alignment hook inside {@code Minecraft.renderFrame}, invoked
 * immediately AFTER {@code CommandEncoder.submit()} and BEFORE {@code GpuSurface.present()} so
 * GPU command execution overlaps the residual wait and present intervals stay flat across both
 * 20 Hz game-tick frames and non-tick frames.
 */
@Mixin(value = Minecraft.class, priority = 10500)
abstract class MinecraftPrePresentPacingMixin {
	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V")
	)
	private void ephemera$onPrePresent(boolean advanceGameTime, CallbackInfo ci) {
		EphemeraPacerRuntime.onPrePresent();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V", shift = At.Shift.AFTER)
	)
	private void ephemera$onPostPresent(boolean advanceGameTime, CallbackInfo ci) {
		EphemeraPacerRuntime.onPostPresent();
	}
}
