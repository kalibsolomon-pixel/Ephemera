package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.RenderPassTextureBinding;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.HashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
abstract class GlRenderPassMixin {
	@Shadow @Final protected HashMap<String, GpuBufferSlice> uniforms;
	@Shadow @Final protected HashMap<String, ?> samplers;
	@Unique private boolean ephemera$dynamicUniformsMayBeBound;

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
		if (buffer != null && !ephemera$dynamicUniformsMayBeBound && DispatchSettings.glDispatch()
				&& DispatchSettings.sameSlice(uniforms.get(name), buffer.slice())) {
			ci.cancel();
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedSlice(String name, GpuBufferSlice slice, CallbackInfo ci) {
		if (!ephemera$dynamicUniformsMayBeBound && DispatchSettings.glDispatch()
				&& DispatchSettings.sameSlice(uniforms.get(name), slice)) {
			ci.cancel();
		}
	}

	@Inject(method = "drawMultipleIndexed", at = @At("HEAD"))
	private <T> void ephemera$allowUniformRebinds(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer,
			IndexType indexType, Collection<String> dynamicUniforms, T data, CallbackInfo ci) {
		// The batch uploader can replace a native UBO binding without updating the regular uniform map.
		ephemera$dynamicUniformsMayBeBound = true;
	}
}
