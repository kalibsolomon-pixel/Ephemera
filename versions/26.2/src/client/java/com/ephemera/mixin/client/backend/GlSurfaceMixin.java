package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.platform.Win32DisplayRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlSurface;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Enables adaptive VSync ({@code glfwSwapInterval(-1)}) on OpenGL when VSync is on and the driver
 * supports {@code WGL_EXT_swap_control_tear} / {@code GLX_EXT_swap_control_tear}, matching
 * Vulkan's {@code FIFO_RELAXED} preference so a single slightly late frame never halves FPS.
 */
@Mixin(GlSurface.class)
abstract class GlSurfaceMixin {
	@WrapOperation(
		method = "configure",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V")
	)
	private void ephemera$preferAdaptiveVsync(int interval, Operation<Void> original) {
		if (interval == 1
			&& EphemeraCommon.config().borderlessFlipOptimization
			&& Win32DisplayRuntime.isBorderlessActive()
			&& (GLFW.glfwExtensionSupported("WGL_EXT_swap_control_tear")
				|| GLFW.glfwExtensionSupported("GLX_EXT_swap_control_tear"))) {
			original.call(-1);
			return;
		}
		original.call(interval);
	}
}
