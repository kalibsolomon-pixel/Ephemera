package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.VulkanDescriptorBindings;
import com.ephemera.render.backend.VulkanDescriptorPlan;
import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import java.util.HashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
	@Shadow protected abstract VkCommandBuffer commandBuffer();

	@Unique private static final int EPHEMERA_TRACKED_VERTEX_SLOTS = 16;
	@Unique private boolean ephemera$cacheEnabled;
	@Unique private boolean ephemera$descriptorCacheEnabled;
	@Unique private boolean ephemera$pooledMaps;
	@Unique private VulkanRenderPipeline ephemera$boundPipeline;
	@Unique private VulkanDescriptorBindings ephemera$bindings;
	@Unique private VulkanBindGroupLayout ephemera$descriptorLayout;
	@Unique private VulkanDescriptorPlan ephemera$descriptorPlan;
	@Unique private long ephemera$vb0Handle;
	@Unique private long ephemera$vb0Offset;
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
		if (!ephemera$descriptorCacheEnabled) {
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
	private void ephemera$fastBindTexture(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
		VulkanDescriptorBindings bindings = ephemera$activeBindings();
		if (bindings == null) {
			return;
		}
		int slot = VulkanDescriptorBindings.slot(name);
		if (slot < 0) {
			return;
		}
		if (view == null && sampler == null) {
			bindings.clearTexture(slot);
			ci.cancel();
			return;
		}
		if (view == null || sampler == null) {
			// Let vanilla throw IllegalArgumentException.
			return;
		}
		VulkanGpuTextureView vkView = (VulkanGpuTextureView) view;
		VulkanGpuSampler vkSampler = (VulkanGpuSampler) sampler;
		if (bindings.textureView(slot) != vkView || bindings.textureSampler(slot) != vkSampler) {
			bindings.setTexture(slot, vkView, vkSampler);
			anyDescriptorDirty = true;
		}
		ci.cancel();
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$unchangedBuffer(String name, GpuBuffer buffer, CallbackInfo ci) {
		VulkanDescriptorBindings bindings = ephemera$activeBindings();
		if (bindings != null && buffer != null) {
			int slot = VulkanDescriptorBindings.slot(name);
			if (slot >= 0) {
				if (!DispatchSettings.sameSlice(bindings.uniform(slot), buffer, 0L, buffer.size())) {
					bindings.uniform(slot, buffer.slice());
					anyDescriptorDirty = true;
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
					bindings.uniform(slot, slice);
					anyDescriptorDirty = true;
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
		if (!ephemera$descriptorCacheEnabled) {
			ephemera$releaseBindings();
			return null;
		}
		if (ephemera$bindings == null) {
			ephemera$bindings = VulkanDescriptorBindings.acquire(uniforms, textures);
		}
		return ephemera$bindings;
	}

	/**
	 * @author Ephemera
	 * @reason Eliminates redundant vkCmdBindVertexBuffers calls and MixinExtras @WrapMethod Operation
	 *         lambda allocations on every terrain/entity/UI draw.
	 */
	@Overwrite
	public void setVertexBuffer(int slot, GpuBufferSlice slice) {
		if (slice == null) {
			if (slot >= 0 && slot < EPHEMERA_TRACKED_VERTEX_SLOTS) {
				ephemera$knownVertexSlots &= ~(1 << slot);
			}
			return;
		}
		long handle = ((VulkanGpuBuffer) slice.buffer()).vkBuffer();
		long offset = slice.offset();
		if (ephemera$cacheEnabled && slot >= 0 && slot < EPHEMERA_TRACKED_VERTEX_SLOTS) {
			if (slot == 0) {
				if ((ephemera$knownVertexSlots & 1) != 0
						&& ephemera$vb0Handle == handle && ephemera$vb0Offset == offset) {
					return;
				}
				ephemera$knownVertexSlots &= ~1;
				ephemera$bindVkVertexBuffer(slot, handle, offset);
				ephemera$vb0Handle = handle;
				ephemera$vb0Offset = offset;
				ephemera$knownVertexSlots |= 1;
				return;
			}
			int bit = 1 << slot;
			int index = slot * 2;
			if ((ephemera$knownVertexSlots & bit) != 0
					&& ephemera$vertexBindings[index] == handle && ephemera$vertexBindings[index + 1] == offset) {
				return;
			}
			ephemera$knownVertexSlots &= ~bit;
			ephemera$bindVkVertexBuffer(slot, handle, offset);
			if (ephemera$vertexBindings == null) {
				ephemera$vertexBindings = new long[EPHEMERA_TRACKED_VERTEX_SLOTS * 2];
			}
			ephemera$vertexBindings[index] = handle;
			ephemera$vertexBindings[index + 1] = offset;
			ephemera$knownVertexSlots |= bit;
			return;
		}
		ephemera$knownVertexSlots = 0;
		ephemera$bindVkVertexBuffer(slot, handle, offset);
	}

	@Unique
	private void ephemera$bindVkVertexBuffer(int slot, long handle, long offset) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK12.vkCmdBindVertexBuffers(commandBuffer(), slot, stack.longs(handle), stack.longs(offset));
		}
	}

	/**
	 * @author Ephemera
	 * @reason Eliminates redundant vkCmdBindIndexBuffer calls and MixinExtras @WrapMethod Operation
	 *         lambda allocations on every indexed draw.
	 */
	@Overwrite
	public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
		int vkType = switch (type) {
			case SHORT -> VK12.VK_INDEX_TYPE_UINT16;
			case INT -> VK12.VK_INDEX_TYPE_UINT32;
		};
		long handle = ((VulkanGpuBuffer) buffer).vkBuffer();
		if (ephemera$cacheEnabled && ephemera$indexType == type && ephemera$indexBuffer == handle) {
			return;
		}
		ephemera$indexType = null;
		VK12.vkCmdBindIndexBuffer(commandBuffer(), handle, 0L, vkType);
		if (ephemera$cacheEnabled) {
			ephemera$indexBuffer = handle;
			ephemera$indexType = type;
		}
	}

	@Redirect(method = "<init>", at = @At(value = "NEW", target = "()Ljava/util/HashMap;"))
	private HashMap<?, ?> ephemera$acquirePassMap() {
		if (RenderSystem.isOnRenderThread()
			&& !VulkanRenderPass.VALIDATION
			&& EphemeraCommon.config().backendVulkanDispatchCache
			&& ((Object) this).getClass() == VulkanRenderPass.class) {
			ephemera$pooledMaps = true;
			return VulkanDescriptorBindings.acquireMap();
		}
		return new HashMap<>();
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$initialScissor(CallbackInfo ci, @Local(argsOnly = true) RenderPass.RenderArea area) {
		ephemera$cacheEnabled = EphemeraCommon.config().backendVulkanDispatchCache;
		ephemera$descriptorCacheEnabled = ephemera$cacheEnabled && !VulkanRenderPass.VALIDATION
			&& ((Object) this).getClass() == VulkanRenderPass.class;
		if (area != null) {
			ephemera$rememberScissor(area.x(), area.y(), area.width(), area.height());
		}
	}

	@WrapMethod(method = "enableScissor")
	private void ephemera$setScissor(int x, int y, int width, int height, Operation<Void> original) {
		if (!ephemera$cacheEnabled || !ephemera$scissorKnown
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

	@Override
	public void ephemera$releaseBindings() {
		if (ephemera$bindings != null) {
			VulkanDescriptorBindings.release(ephemera$bindings);
			ephemera$bindings = null;
		}
		if (ephemera$pooledMaps) {
			ephemera$pooledMaps = false;
			VulkanDescriptorBindings.releaseMap(uniforms);
			VulkanDescriptorBindings.releaseMap(textures);
		}
	}
}
