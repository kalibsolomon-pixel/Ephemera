package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mojang's Vulkan {@code GlslCompiler} compiles every GLSL shader in Minecraft with
 * {@code shaderc_optimization_level_zero} (-O0). Enabling performance optimization
 * (while preserving {@code OpName} debug info for SPIRV-Cross reflection) reduces
 * VGPR/SGPR pressure and improves shader ALU/texture scheduling across the whole game.
 */
@Mixin(GlslCompiler.class)
abstract class VulkanGlslCompilerMixin {
	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_options_set_optimization_level(JI)V"),
		index = 1
	)
	private int ephemera$optimizeSpirv(int originalLevel) {
		return EphemeraCommon.config().backendVulkanModernGpu
			? Shaderc.shaderc_optimization_level_performance
			: originalLevel;
	}
}
