package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 OpenGL render-pass dirty-flag deduplication: avoids needlessly marking pipelines,
 * slot-indexed uniforms/samplers, index buffers, and scissor rectangles dirty when values are
 * unchanged.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlRenderPass")
abstract class GlRenderPassMixin {
	@Shadow protected GlRenderPipeline pipeline;
	@Shadow protected GpuBuffer indexBuffer;
	@Shadow protected IndexType indexType;
	@Shadow protected boolean indexBufferDirty;
	@Shadow @Final private ScissorState defaultScissorState;
	@Shadow @Final private ScissorState scissorState;
	@Shadow protected boolean scissorStateDirty;
	@Shadow @Final protected ReferenceList<Object> uniforms;
	@Shadow @Final protected BooleanList dirtyUniforms;

	@Inject(method = "setPipeline", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipSamePipeline(BackendRenderPipeline requested, CallbackInfo ci) {
		if (DispatchSettings.glDispatch() && pipeline != null && pipeline == requested) {
			ci.cancel();
		}
	}

	@Inject(method = "setUniform", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipUnchangedUniform(int slot, Object value, CallbackInfo ci) {
		if (!DispatchSettings.glDispatch() || slot < 0 || slot >= uniforms.size()) {
			return;
		}
		if (dirtyUniforms.getBoolean(slot)) {
			return;
		}
		Object prev = uniforms.get(slot);
		if (prev == value) {
			ci.cancel();
			return;
		}
		if (prev instanceof GpuBufferSlice p && value instanceof GpuBufferSlice n && DispatchSettings.sameSlice(p, n)) {
			ci.cancel();
			return;
		}
		if (prev instanceof TextureViewAndSampler p && value instanceof TextureViewAndSampler n
				&& p.view() == n.view() && p.sampler() == n.sampler()) {
			ci.cancel();
		}
	}

	@Inject(method = "setIndexBuffer", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipSameIndexBuffer(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
		if (DispatchSettings.glDispatch() && !indexBufferDirty && indexBuffer == buffer && indexType == type) {
			ci.cancel();
		}
	}

	@Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipSameScissor(int x, int y, int width, int height, CallbackInfo ci) {
		if (DispatchSettings.glDispatch() && !scissorStateDirty && scissorState.enabled()
				&& scissorState.x() == x && scissorState.y() == y
				&& scissorState.width() == width && scissorState.height() == height) {
			ci.cancel();
		}
	}

	@Inject(method = "disableScissor", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipDefaultScissor(CallbackInfo ci) {
		if (DispatchSettings.glDispatch() && !scissorStateDirty
				&& scissorState.enabled() == defaultScissorState.enabled()
				&& (!scissorState.enabled()
					|| (scissorState.x() == defaultScissorState.x()
						&& scissorState.y() == defaultScissorState.y()
						&& scissorState.width() == defaultScissorState.width()
						&& scissorState.height() == defaultScissorState.height()))) {
			ci.cancel();
		}
	}
}
