package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.ShaderPatchService;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Unified 26.3 shader-patching choke point: both OpenGL and Vulkan compile GLSL through
 * {@link GlslCompiler#compileToSpv}.
 */
@Mixin(GlslCompiler.class)
public abstract class ShaderSourceMixin {
	@ModifyVariable(method = "compileToSpv", at = @At("HEAD"), argsOnly = true)
	private ShaderSource ephemera$wrapShaderSource(ShaderSource shaderSource,
			@Local(argsOnly = true, ordinal = 0) String name,
			@Local(argsOnly = true) ShaderType type) {
		return ShaderPatchService.wrapForStage(name, type, shaderSource);
	}
}
