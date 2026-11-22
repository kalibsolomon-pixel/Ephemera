package com.ephemera.mixin.client.terrain;

import com.ephemera.core.visuals.ShaderSourcePatcher;
import com.ephemera.render.terrain.gl.GlSingleMdiTerrain;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Binds the {@code EphemeraTerrainBatch} block of the patched terrain programs to
 * {@link GlSingleMdiTerrain#BATCH_UBO_BINDING} (outside the pipeline's own uniforms, so
 * vanilla never binds or validates it) and reports those programs, so single multi-draw only
 * runs once both terrain programs carry the block.
 */
@Mixin(GlProgram.class)
public abstract class GlProgramTerrainBatchMixin {
	@Shadow @Final private int programId;
	@Shadow @Final private String debugLabel;

	/** The active-block loop's only {@code containsKey}: treat the batch block as known so it is not warned about. */
	@WrapOperation(
		method = "setupBindGroupLayouts",
		at = @At(value = "INVOKE", target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z")
	)
	private boolean ephemera$knownBatchBlock(Map<?, ?> uniforms, Object key, Operation<Boolean> original) {
		if (ShaderSourcePatcher.GL_BATCH_BLOCK.equals(key)) {
			return true;
		}
		return original.call(uniforms, key);
	}

	@Inject(method = "setupBindGroupLayouts", at = @At("RETURN"))
	private void ephemera$bindBatchBlock(List<BindGroupLayout> layouts, CallbackInfo ci) {
		int index = GL33C.glGetUniformBlockIndex(this.programId, ShaderSourcePatcher.GL_BATCH_BLOCK);
		if (index != GL31C.GL_INVALID_INDEX) {
			GL33C.glUniformBlockBinding(this.programId, index, GlSingleMdiTerrain.BATCH_UBO_BINDING);
			GlSingleMdiTerrain.get().onProgramBound(this.programId, this.debugLabel);
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void ephemera$programClosed(CallbackInfo ci) {
		GlSingleMdiTerrain.get().onProgramClosed(this.programId);
	}
}
