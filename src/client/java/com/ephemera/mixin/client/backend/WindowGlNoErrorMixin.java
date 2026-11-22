package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlNoErrorContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_NO_ERROR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

@Mixin(Window.class)
abstract class WindowGlNoErrorMixin {
	@Inject(method = "createGlfwWindow", at = @At("HEAD"))
	private static void ephemera$clearNoErrorContext(CallbackInfoReturnable<Long> cir) {
		GlNoErrorContext.clearActive();
	}

	@WrapOperation(method = "createGlfwWindow", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
	private static long ephemera$createNoErrorWindow(int width, int height, CharSequence title, long monitor,
			long sharedContext, Operation<Long> original, @Local(argsOnly = true) GpuBackend backend) {
		if (backend.getClass() == GlBackend.class && GlNoErrorContext.isAllowed()
				&& EphemeraCommon.config().backendGlNoErrorContext && !DispatchSettings.externalShaderPack()) {
			long handle;
			glfwWindowHint(GLFW_CONTEXT_NO_ERROR, GLFW_TRUE);
			try {
				handle = original.call(width, height, title, monitor, sharedContext);
			} finally {
				glfwWindowHint(GLFW_CONTEXT_NO_ERROR, GLFW_FALSE);
			}
			if (handle != 0) {
				GlNoErrorContext.markActive();
				return handle;
			}
		}
		return original.call(width, height, title, monitor, sharedContext);
	}
}
