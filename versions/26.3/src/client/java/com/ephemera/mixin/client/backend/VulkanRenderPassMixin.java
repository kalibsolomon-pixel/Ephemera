package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.VulkanDescriptorPlan;
import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render-pass bind cache and pre-compiled off-heap descriptor pusher for 26.3's
 * slot-indexed {@link VulkanRenderPass}.
 */
@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin implements VulkanRenderPassBridge {
	@Shadow protected VulkanRenderPipeline pipeline;
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private VulkanCommandEncoder encoder;
	@Shadow @Final private VkCommandBuffer commandBuffer;
	@Shadow @Final private boolean hasDepth;
	@Shadow private boolean anyDescriptorDirty;
	@Shadow @Final protected ReferenceList<Object> uniforms;

	@Unique private static final int EPHEMERA_TRACKED_VERTEX_SLOTS = 16;
	// Render-thread persistent 32-byte off-heap scratch: [0..7] vkBuffer, [8..15] offset, [16..31] VkRect2D.
	@Unique private static final long EPHEMERA$RT_SCRATCH = MemoryUtil.nmemCalloc(1L, 32L);
	@Unique private static final int EPHEMERA$RECT_OFFSET = 16;

	@Unique private boolean ephemera$cacheEnabled;
	@Unique private VulkanRenderPipeline ephemera$boundPipeline;
	@Unique private VulkanRenderPipeline ephemera$planPipeline;
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
	@Unique private int ephemera$colorVkFormat;
	@Unique private int ephemera$depthVkFormat;
	@Unique private int ephemera$externalDrawSeq;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$initialState(CallbackInfo ci, @Local(argsOnly = true) RenderPass.RenderArea area) {
		ephemera$cacheEnabled = EphemeraCommon.config().backendVulkanDispatchCache;
		if (area != null) {
			ephemera$rememberScissor(area.x(), area.y(), area.width(), area.height());
		}
	}

	@WrapMethod(method = "setPipeline")
	private void ephemera$bindPipeline(BackendRenderPipeline requested, Operation<Void> original) {
		if (!ephemera$cacheEnabled) {
			ephemera$boundPipeline = null;
			original.call(requested);
			return;
		}
		if (pipeline != null && pipeline == ephemera$boundPipeline && pipeline == requested) {
			return;
		}
		ephemera$boundPipeline = null;
		original.call(requested);
		ephemera$boundPipeline = pipeline;
	}

	@Inject(method = "setUniform", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipUnchangedUniform(int slot, Object value, CallbackInfo ci) {
		if (!ephemera$cacheEnabled || slot < 0 || slot >= uniforms.size()) {
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

	@Inject(method = "pushDescriptors", at = @At("HEAD"), cancellable = true)
	private void ephemera$pushDescriptors(CallbackInfo ci) {
		if (!anyDescriptorDirty) {
			ci.cancel();
			return;
		}
		if (!ephemera$cacheEnabled || pipeline == null) {
			return;
		}
		if (ephemera$planPipeline != pipeline) {
			ephemera$descriptorPlan = VulkanDescriptorPlan.of(pipeline);
			ephemera$planPipeline = pipeline;
		}
		ephemera$descriptorPlan.push(commandBuffer, pipeline.pipelineLayout(), device, encoder, uniforms);
		anyDescriptorDirty = false;
		ci.cancel();
	}

	@WrapMethod(method = "setVertexBuffer")
	private void ephemera$bindVertexBuffer(int slot, GpuBufferSlice slice, Operation<Void> original) {
		if (slice == null) {
			return;
		}
		if (!ephemera$cacheEnabled) {
			ephemera$knownVertexSlots = 0;
			original.call(slot, slice);
			return;
		}
		if (slot < 0 || slot >= EPHEMERA_TRACKED_VERTEX_SLOTS || !(slice.buffer() instanceof VulkanGpuBuffer vkBuf)) {
			original.call(slot, slice);
			return;
		}
		long handle = vkBuf.vkBuffer();
		long offset = slice.offset();
		int bit = 1 << slot;
		int index = slot * 2;
		if ((ephemera$knownVertexSlots & bit) != 0
				&& ephemera$vertexBindings[index] == handle && ephemera$vertexBindings[index + 1] == offset) {
			return;
		}
		ephemera$knownVertexSlots &= ~bit;
		if (EPHEMERA$RT_SCRATCH != 0L && RenderSystem.isOnRenderThread()) {
			MemoryUtil.memPutLong(EPHEMERA$RT_SCRATCH, handle);
			MemoryUtil.memPutLong(EPHEMERA$RT_SCRATCH + 8L, offset);
			VK12.nvkCmdBindVertexBuffers(commandBuffer, slot, 1, EPHEMERA$RT_SCRATCH, EPHEMERA$RT_SCRATCH + 8L);
		} else {
			original.call(slot, slice);
		}
		if (ephemera$vertexBindings == null) {
			ephemera$vertexBindings = new long[EPHEMERA_TRACKED_VERTEX_SLOTS * 2];
		}
		ephemera$vertexBindings[index] = handle;
		ephemera$vertexBindings[index + 1] = offset;
		ephemera$knownVertexSlots |= bit;
	}

	@WrapMethod(method = "setIndexBuffer")
	private void ephemera$bindIndexBuffer(GpuBuffer buffer, IndexType type, Operation<Void> original) {
		if (!ephemera$cacheEnabled || !(buffer instanceof VulkanGpuBuffer vulkanBuffer) || type == null) {
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

	@WrapMethod(method = "enableScissor")
	private void ephemera$setScissor(int x, int y, int width, int height, Operation<Void> original) {
		if (!ephemera$cacheEnabled || !ephemera$scissorKnown
				|| x != ephemera$scissorX || y != ephemera$scissorY
				|| width != ephemera$scissorWidth || height != ephemera$scissorHeight) {
			ephemera$scissorKnown = false;
			if (EPHEMERA$RT_SCRATCH != 0L && RenderSystem.isOnRenderThread()) {
				long rect = EPHEMERA$RT_SCRATCH + EPHEMERA$RECT_OFFSET;
				MemoryUtil.memPutInt(rect + VkRect2D.OFFSET + VkOffset2D.X, x);
				MemoryUtil.memPutInt(rect + VkRect2D.OFFSET + VkOffset2D.Y, y);
				MemoryUtil.memPutInt(rect + VkRect2D.EXTENT + VkExtent2D.WIDTH, width);
				MemoryUtil.memPutInt(rect + VkRect2D.EXTENT + VkExtent2D.HEIGHT, height);
				VK12.nvkCmdSetScissor(commandBuffer, 0, 1, rect);
			} else {
				original.call(x, y, width, height);
			}
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
	public boolean ephemera$isPipelineValid() {
		return pipeline != null;
	}

	@Override
	public int ephemera$externalDrawSeq() {
		return ephemera$externalDrawSeq;
	}

	@Override
	public int ephemera$colorVkFormat() {
		return ephemera$colorVkFormat;
	}

	@Override
	public int ephemera$depthVkFormat() {
		return ephemera$depthVkFormat;
	}

	@Override
	public void ephemera$setAttachmentFormats(int colorVkFormat, int depthVkFormat) {
		ephemera$colorVkFormat = colorVkFormat;
		ephemera$depthVkFormat = depthVkFormat;
	}

	@Override
	public void ephemera$invalidateAfterExternalDraw() {
		ephemera$boundPipeline = null;
		pipeline = null;
		anyDescriptorDirty = true;
		ephemera$knownVertexSlots = 0;
		ephemera$indexType = null;
		ephemera$externalDrawSeq++;
	}

	@Override
	public void ephemera$releaseBindings() {
		// 26.3 binds directly into VulkanRenderPass.uniforms without pooled binding arrays.
	}
}
