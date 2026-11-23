package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlStateCache;
import com.ephemera.render.backend.RenderPassTextureBinding;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
abstract class GlRenderPassMixin implements DispatchSettings.GlUniformState, GlStateCache.RenderPassState {
	@Shadow @Final protected HashMap<String, GpuBufferSlice> uniforms;
	@Shadow @Final protected HashMap<String, ?> samplers;
	@Shadow @Final protected Set<String> dirtyUniforms;
	@Shadow @Final protected int colorAttachmentCount;
	@Shadow public abstract boolean isScissorEnabled();
	@Shadow public abstract int getScissorX();
	@Shadow public abstract int getScissorY();
	@Shadow public abstract int getScissorWidth();
	@Shadow public abstract int getScissorHeight();
	@Unique private Set<String> ephemera$dirtyDynamicUniforms;

	@Override
	public boolean ephemera$hasDirtyUniforms() {
		return !dirtyUniforms.isEmpty();
	}

	@Override
	public int ephemera$colorAttachmentCount() {
		return colorAttachmentCount;
	}

	@Override
	public void ephemera$applyScissor() {
		if (isScissorEnabled()) {
			GlStateManager._enableScissorTest();
			GlStateManager._scissorBox(getScissorX(), getScissorY(), getScissorWidth(), getScissorHeight());
		} else {
			GlStateManager._disableScissorTest();
		}
	}

	@Inject(method = "bindTexture", at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedTexture(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
		if (sampler != null && DispatchSettings.glDispatch()
				&& samplers.get(name) instanceof RenderPassTextureBinding.OpenGl previous
				&& previous.ephemera$view() == view && previous.ephemera$sampler() == sampler) {
			ci.cancel();
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedBuffer(String name, GpuBuffer buffer, CallbackInfo ci) {
		if (buffer != null && !ephemera$consumeDynamicUniform(name) && DispatchSettings.glDispatch()
				&& DispatchSettings.sameSlice(uniforms.get(name), buffer.slice())) {
			ci.cancel();
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedSlice(String name, GpuBufferSlice slice, CallbackInfo ci) {
		if (!ephemera$consumeDynamicUniform(name) && DispatchSettings.glDispatch()
				&& DispatchSettings.sameSlice(uniforms.get(name), slice)) {
			ci.cancel();
		}
	}

	@Unique
	private boolean ephemera$consumeDynamicUniform(String name) {
		return ephemera$dirtyDynamicUniforms != null && ephemera$dirtyDynamicUniforms.remove(name);
	}

	@Inject(method = "drawMultipleIndexed", at = @At("RETURN"))
	private <T> void ephemera$allowUniformRebinds(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer,
			IndexType indexType, Collection<String> dynamicUniforms, T data, CallbackInfo ci) {
		if (!dynamicUniforms.isEmpty()) {
			// Preserve regular slices for vanilla setup and let the next setter restore a batch-overridden binding.
			if (ephemera$dirtyDynamicUniforms == null) {
				ephemera$dirtyDynamicUniforms = new HashSet<>();
			}
			ephemera$dirtyDynamicUniforms.addAll(dynamicUniforms);
		}
	}
}
