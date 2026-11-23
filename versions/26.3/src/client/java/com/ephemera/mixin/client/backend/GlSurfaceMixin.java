package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.platform.Win32DisplayRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Enables adaptive VSync ({@code SDL_GL_SetSwapInterval(-1)}) on 26.3 OpenGL when VSync is on and
 * the driver supports late-swap tearing, matching Vulkan's {@code FIFO_RELAXED} preference.
 */
@Mixin(GlSurface.class)
abstract class GlSurfaceMixin {
	@WrapOperation(
		method = "configure",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetSwapInterval(I)Z")
	)
	private boolean ephemera$preferAdaptiveVsync(int interval, Operation<Boolean> original) {
		if (interval == 1
			&& EphemeraCommon.config().borderlessFlipOptimization
			&& Win32DisplayRuntime.isBorderlessActive()) {
			if (original.call(-1)) {
				return true;
			}
		}
		return original.call(interval);
	}
}
