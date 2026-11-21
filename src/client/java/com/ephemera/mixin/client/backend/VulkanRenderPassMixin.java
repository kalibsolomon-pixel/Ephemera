package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.RenderPassTextureBinding;
import com.ephemera.render.backend.VulkanDescriptorBindings;
import com.ephemera.render.backend.VulkanDescriptorPlan;
import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import java.util.HashMap;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin implements VulkanRenderPassBridge {
	@Shadow protected VulkanRenderPipeline pipeline;
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private VulkanCommandEncoder encoder;
	@Shadow @Final private VkCommandBuffer commandBuffer;
	@Shadow @Final private boolean hasDepth;
	@Shadow private boolean anyDescriptorDirty;
	@Shadow @Final protected HashMap<String, GpuBufferSlice> uniforms;
	@Shadow @Final protected HashMap<String, ?> textures;

	@Unique private static final int EPHEMERA_TRACKED_VERTEX_SLOTS = 16;
	@Unique private VulkanRenderPipeline ephemera$boundPipeline;
	@Unique private VulkanDescriptorBindings ephemera$bindings;
	@Unique private VulkanBindGroupLayout ephemera$descriptorLayout;
	@Unique private VulkanDescriptorPlan ephemera$descriptorPlan;
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
		VulkanDescriptorBindings bindings = ephemera$activeBindings();
		if (bindings == null || view == null || sampler == null) {
			return;
		}
		int slot = VulkanDescriptorBindings.slot(name);
		if (slot >= 0 && bindings.texture(slot) instanceof RenderPassTextureBinding.Vulkan previous
				&& previous.ephemera$view() == view && previous.ephemera$sampler() == sampler) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "bindTexture", at = @At(value = "INVOKE",
		target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object ephemera$storeTexture(HashMap<?, ?> map, Object key, Object value, Operation<Object> original) {
		Object previous = original.call(map, key, value);
		ephemera$recordTexture((String) key, value);
		return previous;
	}

	@WrapOperation(method = "bindTexture", at = @At(value = "INVOKE",
		target = "Ljava/util/HashMap;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object ephemera$removeTexture(HashMap<?, ?> map, Object key, Operation<Object> original) {
		Object previous = original.call(map, key);
		ephemera$recordTexture((String) key, null);
		return previous;
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedBuffer(String name, GpuBuffer buffer, CallbackInfo ci) {
		VulkanDescriptorBindings bindings = ephemera$activeBindings();
		if (bindings != null && buffer != null) {
			int slot = VulkanDescriptorBindings.slot(name);
			if (slot >= 0) {
				if (!DispatchSettings.sameSlice(bindings.uniform(slot), buffer, 0L, buffer.size())) {
					ephemera$storeUniform(bindings, slot, name, buffer.slice());
				}
				ci.cancel();
			}
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedSlice(String name, GpuBufferSlice slice, CallbackInfo ci) {
		VulkanDescriptorBindings bindings = ephemera$activeBindings();
		if (bindings != null) {
			int slot = VulkanDescriptorBindings.slot(name);
			if (slot >= 0) {
				if (!DispatchSettings.sameSlice(bindings.uniform(slot), slice)) {
					ephemera$storeUniform(bindings, slot, name, slice);
				}
				ci.cancel();
			}
		}
	}

	@Inject(method = "pushDescriptors", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vulkan/VulkanRenderPipeline;layout()Lcom/mojang/blaze3d/vulkan/VulkanBindGroupLayout;"),
		cancellable = true)
	private void ephemera$pushDescriptors(CallbackInfo ci) {
		VulkanDescriptorBindings bindings = ephemera$activeBindings();
		if (bindings == null) {
			return;
		}
		VulkanBindGroupLayout layout = pipeline.layout();
		if (ephemera$descriptorLayout != layout) {
			ephemera$descriptorPlan = VulkanDescriptorPlan.of(layout);
			ephemera$descriptorLayout = layout;
		}
		ephemera$descriptorPlan.push(commandBuffer, pipeline.pipelineLayout(), device, encoder, bindings, uniforms, textures);
		anyDescriptorDirty = false;
		ci.cancel();
	}

	@Unique
	private VulkanDescriptorBindings ephemera$activeBindings() {
		if (!ephemera$descriptorDispatchEnabled()) {
			ephemera$bindings = null;
			return null;
		}
		if (ephemera$bindings == null) {
			ephemera$bindings = new VulkanDescriptorBindings(uniforms, textures);
		}
		return ephemera$bindings;
	}

	@Unique
	private void ephemera$recordTexture(String name, Object binding) {
		if (ephemera$bindings != null) {
			int slot = VulkanDescriptorBindings.slot(name);
			if (slot >= 0) {
				ephemera$bindings.texture(slot, binding);
			}
		}
	}

	@Unique
	private void ephemera$storeUniform(VulkanDescriptorBindings bindings, int slot, String name, GpuBufferSlice slice) {
		uniforms.put(name, slice);
		bindings.uniform(slot, slice);
		anyDescriptorDirty = true;
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

	@Override
	public VulkanDevice ephemera$device() {
		return device;
	}

	@Override
	public VulkanCommandEncoder ephemera$encoder() {
		return encoder;
	}

	@Override
	public VkCommandBuffer ephemera$commandBuffer() {
		return commandBuffer;
	}

	@Override
	public boolean ephemera$hasDepth() {
		return hasDepth;
	}

	@Override
	public void ephemera$invalidateAfterExternalDraw() {
		ephemera$boundPipeline = null;
		pipeline = null;
		anyDescriptorDirty = true;
	}
}
