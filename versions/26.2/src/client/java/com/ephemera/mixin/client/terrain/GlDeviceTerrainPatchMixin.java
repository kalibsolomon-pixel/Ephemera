package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.gl.GlSingleMdiTerrain;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells {@link GlSingleMdiTerrain} which pipeline is compiling, so the terrain fragment patch is
 * only applied when the same pipeline's vertex stage is the patched {@code core/terrain} (the
 * fragment reads a varying only that vertex stage writes).
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
abstract class GlDeviceTerrainPatchMixin {
	@Inject(method = "compileProgram", at = @At("HEAD"))
	private void ephemera$beginProgram(RenderPipeline pipeline, ShaderSource source, CallbackInfoReturnable<?> cir) {
		GlSingleMdiTerrain.beginProgramCompile(pipeline.getVertexShader(), pipeline.getShaderDefines());
	}

	@Inject(method = "compileProgram", at = @At("RETURN"))
	private void ephemera$endProgram(RenderPipeline pipeline, ShaderSource source, CallbackInfoReturnable<?> cir) {
		GlSingleMdiTerrain.endProgramCompile();
	}
}
