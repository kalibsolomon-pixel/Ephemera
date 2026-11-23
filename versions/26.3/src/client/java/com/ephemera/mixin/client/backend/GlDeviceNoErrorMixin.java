package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlNoErrorContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Enables {@code GL_KHR_no_error} via SDL's {@code SDL_GL_CONTEXT_NO_ERROR} attribute when
 * creating the OpenGL context in 26.3, skipping driver-side error validation on every GL call
 * when no debug logging or external shader pack is active.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
abstract class GlDeviceNoErrorMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_CreateContext(J)J"))
	private long ephemera$createNoErrorContext(long windowHandle, Operation<Long> original,
			@Local(argsOnly = true) GpuDebugOptions debugOptions) {
		GlNoErrorContext.clearActive();
		boolean allowed = debugOptions != null
			&& debugOptions.logLevel() == 0
			&& !debugOptions.synchronousLogs()
			&& !debugOptions.useLabels()
			&& EphemeraCommon.config().backendGlNoErrorContext
			&& !DispatchSettings.externalShaderPack();
		if (allowed) {
			long ctx;
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_NO_ERROR, 1);
			try {
				ctx = original.call(windowHandle);
			} finally {
				SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_NO_ERROR, 0);
			}
			if (ctx != 0L) {
				GlNoErrorContext.markActive();
				return ctx;
			}
		}
		return original.call(windowHandle);
	}
}
