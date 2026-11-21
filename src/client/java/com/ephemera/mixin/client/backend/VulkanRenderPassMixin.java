package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.RenderPassTextureBinding;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import java.util.HashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin {
	@Shadow protected VulkanRenderPipeline pipeline;
	@Shadow @Final protected HashMap<String, GpuBufferSlice> uniforms;
	@Shadow @Final protected HashMap<String, ?> textures;

	@Unique private static final int EPHEMERA_TRACKED_VERTEX_SLOTS = 16;
	@Unique private VulkanRenderPipeline ephemera$boundPipeline;
	@Unique private long[] ephemera$vertexBindings;
	@Unique private int ephemera$knownVertexSlots;
	@Unique private long ephemera$indexBuffer;
	@Unique private IndexType ephemera$indexType;
	@Unique private boolean ephemera$scissorKnown;
	@Unique private int ephemera$scissorX;
	@Unique private int ephemera$scissorY;
	@Unique private int ephemera$scissorWidth;
	@Unique private int ephemera$scissorHeight;

	@WrapMethod(method = "setPipeline")
	private void ephemera$bindPipeline(RenderPipeline requested, Operation<Void> original) {
		if (!ephemera$descriptorDispatchEnabled()) {
			ephemera$boundPipeline = null;
			original.call(requested);
			return;
		}
		if (pipeline != null && pipeline == ephemera$boundPipeline && pipeline.info() == requested) {
			return;
		}
		ephemera$boundPipeline = null;
		original.call(requested);
		ephemera$boundPipeline = pipeline;
	}

	@Inject(method = "bindTexture", at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedTexture(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
		if (view != null && sampler != null && ephemera$descriptorDispatchEnabled()
				&& textures.get(name) instanceof RenderPassTextureBinding.Vulkan previous
				&& previous.ephemera$view() == view && previous.ephemera$sampler() == sampler) {
			ci.cancel();
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedBuffer(String name, GpuBuffer buffer, CallbackInfo ci) {
		if (buffer != null && ephemera$descriptorDispatchEnabled()
				&& DispatchSettings.sameSlice(uniforms.get(name), buffer.slice())) {
			ci.cancel();
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedSlice(String name, GpuBufferSlice slice, CallbackInfo ci) {
		if (ephemera$descriptorDispatchEnabled() && DispatchSettings.sameSlice(uniforms.get(name), slice)) {
			ci.cancel();
		}
	}

	@WrapMethod(method = "setVertexBuffer")
	private void ephemera$bindVertexBuffer(int slot, GpuBufferSlice slice, Operation<Void> original) {
		if (!EphemeraCommon.config().backendVulkanDispatchCache) {
			ephemera$knownVertexSlots = 0;
			original.call(slot, slice);
			return;
		}
		if (slot < 0 || slot >= EPHEMERA_TRACKED_VERTEX_SLOTS
				|| (slice != null && !(slice.buffer() instanceof VulkanGpuBuffer))) {
			original.call(slot, slice);
			return;
		}
		long handle = slice == null ? 0 : ((VulkanGpuBuffer) slice.buffer()).vkBuffer();
		long offset = slice == null ? 0 : slice.offset();
		int bit = 1 << slot;
		int index = slot * 2;
		if ((ephemera$knownVertexSlots & bit) != 0
				&& ephemera$vertexBindings[index] == handle && ephemera$vertexBindings[index + 1] == offset) {
			return;
		}
		ephemera$knownVertexSlots &= ~bit;
		original.call(slot, slice);
		if (ephemera$vertexBindings == null) {
			ephemera$vertexBindings = new long[EPHEMERA_TRACKED_VERTEX_SLOTS * 2];
		}
		ephemera$vertexBindings[index] = handle;
		ephemera$vertexBindings[index + 1] = offset;
		ephemera$knownVertexSlots |= bit;
	}

	@WrapMethod(method = "setIndexBuffer")
	private void ephemera$bindIndexBuffer(GpuBuffer buffer, IndexType type, Operation<Void> original) {
		if (!EphemeraCommon.config().backendVulkanDispatchCache
				|| !(buffer instanceof VulkanGpuBuffer vulkanBuffer) || type == null) {
			ephemera$indexType = null;
			original.call(buffer, type);
			return;
		}
		long handle = vulkanBuffer.vkBuffer();
		if (ephemera$indexType == type && ephemera$indexBuffer == handle) {
			return;
		}
		ephemera$indexType = null;
		original.call(buffer, type);
		ephemera$indexBuffer = handle;
		ephemera$indexType = type;
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$initialScissor(CallbackInfo ci, @Local(argsOnly = true) RenderPass.RenderArea area) {
		if (area != null) {
			ephemera$rememberScissor(area.x(), area.y(), area.width(), area.height());
		}
	}

	@WrapMethod(method = "enableScissor")
	private void ephemera$setScissor(int x, int y, int width, int height, Operation<Void> original) {
		if (!EphemeraCommon.config().backendVulkanDispatchCache || !ephemera$scissorKnown
				|| x != ephemera$scissorX || y != ephemera$scissorY
				|| width != ephemera$scissorWidth || height != ephemera$scissorHeight) {
			ephemera$scissorKnown = false;
			original.call(x, y, width, height);
			ephemera$rememberScissor(x, y, width, height);
		}
	}

	@Unique
	private void ephemera$rememberScissor(int x, int y, int width, int height) {
		ephemera$scissorX = x;
		ephemera$scissorY = y;
		ephemera$scissorWidth = width;
		ephemera$scissorHeight = height;
		ephemera$scissorKnown = true;
	}

	@Unique
	private static boolean ephemera$descriptorDispatchEnabled() {
		return EphemeraCommon.config().backendVulkanDispatchCache && !VulkanRenderPass.VALIDATION;
	}
}
