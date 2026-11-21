package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.VulkanAsyncPresenter;
import com.ephemera.render.backend.VulkanDiagTracker;
import com.ephemera.render.backend.VulkanEncoderBridge;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Blaze3D Vulkan's unconditional {@code ALL_COMMANDS | MEMORY_READ | MEMORY_WRITE}
 * full-GPU drain barriers after every single buffer/texture upload and render pass with
 * coalesced, stage-scoped Synchronization2 barriers (no redundant {@code MEMORY_READ} source
 * access flushes, and consecutive uploads coalesce into a single barrier before the next
 * render pass or dependent transfer).
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderMixin implements VulkanEncoderBridge {
	@Unique
	private static final long EPHEMERA$STAGE_TRANSFER = KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR;
	@Unique
	private static final long EPHEMERA$STAGE_ALL_GRAPHICS = KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR;
	@Unique
	private static final long EPHEMERA$STAGE_COMPUTE = KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR;
	@Unique
	private static final long EPHEMERA$STAGE_DRAW_INDIRECT = KHRSynchronization2.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR;
	@Unique
	private static final long EPHEMERA$STAGE_MESH = EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
	@Unique
	private static final long EPHEMERA$STAGE_TASK = EXTMeshShader.VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT;

	@Unique
	private static final long EPHEMERA$ACCESS_TRANSFER_WRITE = KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR;
	@Unique
	private static final long EPHEMERA$ACCESS_GRAPHICS_WRITE =
		KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR;
	@Unique
	private static final long EPHEMERA$ACCESS_ALL_CONSUMERS =
		KHRSynchronization2.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_INDEX_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_UNIFORM_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR;

	@Shadow @Final private VulkanDevice device;
	@Shadow private VkCommandBuffer currentCommandBuffer;
	@Shadow protected abstract VkCommandBuffer commandBuffer();
	@Shadow protected abstract void memoryBarrier(MemoryStack stack);

	@Unique private boolean ephemera$pendingTransfer;
	@Unique private boolean ephemera$pendingGraphics;
	@Unique private long ephemera$submitStartNs;
	@Unique private long ephemera$waitStartNs;

	@Override
	public VulkanDevice ephemera$device() {
		return device;
	}

	@Override
	public VkCommandBuffer ephemera$commandBuffer() {
		return commandBuffer();
	}

	@Override
	public void ephemera$markTransferWrite() {
		ephemera$pendingTransfer = true;
	}

	@Override
	public void ephemera$flushBarriers() {
		if (!ephemera$pendingTransfer && !ephemera$pendingGraphics) {
			return;
		}
		if (currentCommandBuffer == null) {
			ephemera$pendingTransfer = false;
			ephemera$pendingGraphics = false;
			return;
		}
		long srcStage = 0L;
		long srcAccess = 0L;
		if (ephemera$pendingTransfer) {
			srcStage |= EPHEMERA$STAGE_TRANSFER;
			srcAccess |= EPHEMERA$ACCESS_TRANSFER_WRITE;
			ephemera$pendingTransfer = false;
		}
		if (ephemera$pendingGraphics) {
			srcStage |= EPHEMERA$STAGE_ALL_GRAPHICS;
			srcAccess |= EPHEMERA$ACCESS_GRAPHICS_WRITE;
			ephemera$pendingGraphics = false;
		}
		long dstStage = EPHEMERA$STAGE_ALL_GRAPHICS | EPHEMERA$STAGE_DRAW_INDIRECT | EPHEMERA$STAGE_COMPUTE | EPHEMERA$STAGE_TRANSFER;
		if (VulkanModernCapabilities.get().meshShaderEnabled()) {
			dstStage |= EPHEMERA$STAGE_MESH | (VulkanModernCapabilities.get().taskShaderEnabled() ? EPHEMERA$STAGE_TASK : 0L);
		}
		ephemera$emitBarrier(currentCommandBuffer, srcStage, srcAccess, dstStage, EPHEMERA$ACCESS_ALL_CONSUMERS);
	}

	@Override
	public void ephemera$barrierComputeToMeshDraw() {
		VkCommandBuffer cmd = commandBuffer();
		long dstStage = EPHEMERA$STAGE_DRAW_INDIRECT | EPHEMERA$STAGE_MESH
			| (VulkanModernCapabilities.get().taskShaderEnabled() ? EPHEMERA$STAGE_TASK : 0L);
		long dstAccess = KHRSynchronization2.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR;
		ephemera$emitBarrier(cmd, EPHEMERA$STAGE_COMPUTE, KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR,
			dstStage, dstAccess);
	}

	@Unique
	private void ephemera$flushGraphicsOnly() {
		if (!ephemera$pendingGraphics || currentCommandBuffer == null) {
			ephemera$pendingGraphics = false;
			return;
		}
		ephemera$pendingGraphics = false;
		long dstStage = EPHEMERA$STAGE_ALL_GRAPHICS | EPHEMERA$STAGE_TRANSFER | EPHEMERA$STAGE_COMPUTE;
		ephemera$emitBarrier(currentCommandBuffer, EPHEMERA$STAGE_ALL_GRAPHICS, EPHEMERA$ACCESS_GRAPHICS_WRITE,
			dstStage, EPHEMERA$ACCESS_ALL_CONSUMERS);
	}

	@Unique
	private static void ephemera$emitBarrier(VkCommandBuffer cmd, long srcStage, long srcAccess, long dstStage, long dstAccess) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(srcStage)
				.srcAccessMask(srcAccess)
				.dstStageMask(dstStage)
				.dstAccessMask(dstAccess);
			VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(barrier);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
		}
	}

	// --- Flush before render passes and before closing/splitting the command buffer ---

	@Inject(method = "createRenderPass", at = @At("HEAD"))
	private void ephemera$flushBeforeRenderPass(CallbackInfoReturnable<?> cir) {
		ephemera$flushBarriers();
	}

	@Inject(method = "endCommandBuffer", at = @At("HEAD"))
	private void ephemera$flushBeforeEndCommandBuffer(CallbackInfo ci) {
		ephemera$flushBarriers();
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void ephemera$diagSubmitStart(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) {
			ephemera$submitStartNs = System.nanoTime();
		}
		// Ensure any previous frame's off-thread vkQueuePresentKHR has finished before touching VkQueue.
		VulkanAsyncPresenter.awaitIdle();
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void ephemera$diagSubmitEnd(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.recordSubmit(System.nanoTime() - ephemera$submitStartNs);
		}
	}

	@Inject(method = "awaitSubmitCompletion", at = @At("HEAD"))
	private void ephemera$diagWaitStart(long submitIndex, long timeoutNs, CallbackInfoReturnable<Boolean> cir) {
		if (VulkanDiagTracker.ENABLED) {
			ephemera$waitStartNs = System.nanoTime();
		}
	}

	@Inject(method = "awaitSubmitCompletion", at = @At("RETURN"))
	private void ephemera$diagWaitEnd(long submitIndex, long timeoutNs, CallbackInfoReturnable<Boolean> cir) {
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.recordWaitSem(System.nanoTime() - ephemera$waitStartNs);
		}
	}

	// --- Render pass end: defer graphics output barrier ---

	@Redirect(
		method = "submitRenderPass",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferRenderPassBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingGraphics = true;
		} else {
			memoryBarrier(stack);
		}
	}

	// --- Staging uploads (fresh staging source): wait only for prior graphics reads, coalesce transfer writes ---

	@Inject(method = "writeToBuffer", at = @At("HEAD"))
	private void ephemera$syncBeforeWriteBuffer(CallbackInfo ci) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$flushGraphicsOnly();
		}
	}

	@Redirect(
		method = "writeToBuffer",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferWriteBufferBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingTransfer = true;
		} else {
			memoryBarrier(stack);
		}
	}

	@Inject(method = "writeToTexture", at = @At("HEAD"))
	private void ephemera$syncBeforeWriteTexture(CallbackInfo ci) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$flushGraphicsOnly();
		}
	}

	@Redirect(
		method = "writeToTexture",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferWriteTextureBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingTransfer = true;
		} else {
			memoryBarrier(stack);
		}
	}

	// --- GPU-to-GPU copies & clears: flush pending writes first, then defer transfer write barrier ---

	@Inject(method = {"copyToBuffer", "copyBufferToTexture", "copyTextureToTexture",
		"clearColorTexture", "clearDepthTexture",
		"clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V"},
		at = @At("HEAD"))
	private void ephemera$syncBeforeDependentTransfer(CallbackInfo ci) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$flushBarriers();
		}
	}

	@Redirect(
		method = {"copyToBuffer", "copyBufferToTexture", "copyTextureToTexture",
			"clearColorTexture", "clearDepthTexture",
			"clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V"},
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferDependentTransferBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingTransfer = true;
		} else {
			memoryBarrier(stack);
		}
	}

	// --- GPU-to-CPU readback (screenshots / pixel reads): flush first, then narrow transfer->host barrier ---

	@Inject(
		method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		at = @At("HEAD")
	)
	private void ephemera$syncBeforeReadback(CallbackInfo ci) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$flushBarriers();
		}
	}

	@Redirect(
		method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$narrowReadbackBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(EPHEMERA$STAGE_TRANSFER)
				.srcAccessMask(EPHEMERA$ACCESS_TRANSFER_WRITE)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_HOST_BIT_KHR | EPHEMERA$STAGE_TRANSFER | EPHEMERA$STAGE_ALL_GRAPHICS)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_HOST_READ_BIT_KHR | EPHEMERA$ACCESS_ALL_CONSUMERS);
			VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(barrier);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer(), dep);
		} else {
			memoryBarrier(stack);
		}
	}
}
