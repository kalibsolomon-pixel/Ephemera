package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.ShaderPatchService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Unified 26.3 shader-patching choke point: both OpenGL and Vulkan compile GLSL through
 * {@link GlslCompiler#compileToSpv}. Native patched includes are released immediately once
 * {@code shaderc_compile_into_spv} returns or throws.
 */
@Mixin(GlslCompiler.class)
public abstract class ShaderSourceMixin {
	@ModifyVariable(method = "compileToSpv", at = @At("HEAD"), argsOnly = true)
	private ShaderSource ephemera$wrapShaderSource(ShaderSource shaderSource,
			@Local(argsOnly = true, ordinal = 0) String name,
			@Local(argsOnly = true) ShaderType type) {
		return ShaderPatchService.wrapForStage(name, type, shaderSource);
	}

	@WrapOperation(
		method = "compileToSpv",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_into_spv(JLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;J)J"
		)
	)
	private long ephemera$releasePatchedIncludesAfterCompile(
			long compiler, ByteBuffer source, int kind, ByteBuffer inputFileName,
			ByteBuffer entryPoint, long options, Operation<Long> original,
			@Local(argsOnly = true) ShaderSource shaderSource) {
		try {
			return original.call(compiler, source, kind, inputFileName, entryPoint, options);
		} finally {
			ShaderPatchService.releaseStageResources(shaderSource);
		}
	}
}
