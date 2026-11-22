package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.VulkanAsyncPresenter;
import com.ephemera.render.backend.VulkanDescriptorPlan;
import com.ephemera.render.backend.VulkanDiagTracker;
import com.ephemera.render.backend.VulkanEncoderBridge;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Blaze3D Vulkan's unconditional {@code ALL_COMMANDS | MEMORY_READ | MEMORY_WRITE}
 * full-GPU drain barriers after every single buffer/texture upload and render pass with
 * coalesced, stage-scoped Synchronization2 barriers (no redundant {@code MEMORY_READ} source
 * access flushes, and consecutive uploads coalesce into a single barrier before the next
 * render pass or dependent transfer).
 *
 * <p>Also hands {@code vkQueueSubmit2KHR} to {@link VulkanAsyncPresenter}'s FIFO worker and
 * records barriers / semaphore polls through one preallocated off-heap scratch block per encoder
 * (no {@code MemoryStack} push or struct setup per call on the render thread).
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

	// Scratch block layout: VkMemoryBarrier2 | VkDependencyInfo | uint64 semaphore counter value
	@Unique
	private static final int EPHEMERA$DEP_OFFSET = VkMemoryBarrier2.SIZEOF;
	@Unique
	private static final int EPHEMERA$SEM_VALUE_OFFSET = EPHEMERA$DEP_OFFSET + VkDependencyInfo.SIZEOF;
	@Unique
	private static final int EPHEMERA$SCRATCH_SIZE = (EPHEMERA$SEM_VALUE_OFFSET + 8 + 15) & ~15;

	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private long submitSemaphore;
	@Shadow private long currentSubmitIndex;
	@Shadow private long completedSubmitIndex;
	@Shadow private VkCommandBuffer currentCommandBuffer;
	@Shadow private VulkanRenderPass currentRenderPass;
	@Shadow protected abstract VkCommandBuffer commandBuffer();
	@Shadow protected abstract void memoryBarrier(MemoryStack stack);

	@Unique private boolean ephemera$pendingTransfer;
	@Unique private boolean ephemera$pendingGraphics;
	@Unique private final long[] ephemera$transferTargets = new long[8];
	@Unique private int ephemera$transferTargetCount;
	@Unique private long ephemera$zeroPollSubmitIndex = -1L;
	@Unique private long ephemera$submitStartNs;
	@Unique private long ephemera$waitStartNs;
	@Unique private long ephemera$scratch;

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
		ephemera$markUntrackedTransfer();
	}

	/**
	 * A transfer write whose destination is not recorded (copies, clears, mesh-arena uploads):
	 * saturates the target list so the next staging upload treats any destination as a WAW
	 * hazard and flushes instead of coalescing.
	 */
	@Unique
	private void ephemera$markUntrackedTransfer() {
		ephemera$pendingTransfer = true;
		ephemera$transferTargetCount = ephemera$transferTargets.length;
	}

	@Override
	public void ephemera$flushBarriers() {
		if (!ephemera$pendingTransfer && !ephemera$pendingGraphics) {
			return;
		}
		if (currentCommandBuffer == null) {
			ephemera$pendingTransfer = false;
			ephemera$pendingGraphics = false;
			ephemera$transferTargetCount = 0;
			return;
		}
		long srcStage = 0L;
		long srcAccess = 0L;
		if (ephemera$pendingTransfer) {
			srcStage |= EPHEMERA$STAGE_TRANSFER;
			srcAccess |= EPHEMERA$ACCESS_TRANSFER_WRITE;
			ephemera$pendingTransfer = false;
			ephemera$transferTargetCount = 0;
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

	/** Render-thread-only scratch block (lazily allocated); 0 off the render thread or after destroy. */
	@Unique
	private long ephemera$scratch() {
		long scratch = ephemera$scratch;
		if (scratch != 0L || !RenderSystem.isOnRenderThread()) {
			return scratch;
		}
		scratch = MemoryUtil.nmemCalloc(1, EPHEMERA$SCRATCH_SIZE);
		if (scratch == 0L) {
			return 0L;
		}
		long dep = scratch + EPHEMERA$DEP_OFFSET;
		MemoryUtil.memPutInt(scratch + VkMemoryBarrier2.STYPE, KHRSynchronization2.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2_KHR);
		MemoryUtil.memPutInt(dep + VkDependencyInfo.STYPE, KHRSynchronization2.VK_STRUCTURE_TYPE_DEPENDENCY_INFO_KHR);
		MemoryUtil.memPutInt(dep + VkDependencyInfo.MEMORYBARRIERCOUNT, 1);
		MemoryUtil.memPutAddress(dep + VkDependencyInfo.PMEMORYBARRIERS, scratch);
		ephemera$scratch = scratch;
		return scratch;
	}

	@Unique
	private void ephemera$emitBarrier(VkCommandBuffer cmd, long srcStage, long srcAccess, long dstStage, long dstAccess) {
		long scratch = ephemera$scratch();
		if (scratch != 0L) {
			MemoryUtil.memPutLong(scratch + VkMemoryBarrier2.SRCSTAGEMASK, srcStage);
			MemoryUtil.memPutLong(scratch + VkMemoryBarrier2.SRCACCESSMASK, srcAccess);
			MemoryUtil.memPutLong(scratch + VkMemoryBarrier2.DSTSTAGEMASK, dstStage);
			MemoryUtil.memPutLong(scratch + VkMemoryBarrier2.DSTACCESSMASK, dstAccess);
			KHRSynchronization2.nvkCmdPipelineBarrier2KHR(cmd, scratch + EPHEMERA$DEP_OFFSET);
			return;
		}
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

	/** Current {@code submitSemaphore} counter value, or -1 on failure. */
	@Unique
	private long ephemera$querySubmitCounter() {
		long scratch = ephemera$scratch();
		if (scratch != 0L) {
			long pValue = scratch + EPHEMERA$SEM_VALUE_OFFSET;
			int res = VK12.nvkGetSemaphoreCounterValue(device.vkDevice(), submitSemaphore, pValue);
			return res == VK12.VK_SUCCESS ? MemoryUtil.memGetLong(pValue) : -1L;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pVal = stack.callocLong(1);
			int res = VK12.vkGetSemaphoreCounterValue(device.vkDevice(), submitSemaphore, pVal);
			return res == VK12.VK_SUCCESS ? pVal.get(0) : -1L;
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
	}

	/**
	 * Hands {@code vkQueueSubmit2KHR} to the FIFO {@link VulkanAsyncPresenter} worker. At this
	 * point {@code currentSubmitIndex} is still the timeline value this submission signals.
	 */
	@WrapOperation(
		method = "submit",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;close()V")
	)
	private void ephemera$asyncQueueSubmit(VulkanQueue.Submission submission, Operation<Void> original) {
		if (VulkanAsyncPresenter.isAsyncSubmitEnabled()) {
			VulkanAsyncPresenter.enqueueSubmit(submission, currentSubmitIndex);
			return;
		}
		VulkanAsyncPresenter.awaitIdle();
		original.call(submission);
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void ephemera$diagSubmitEnd(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.recordSubmit(System.nanoTime() - ephemera$submitStartNs);
		}
	}

	/** {@code destroy()} closes the submission + waits the queue; {@code getTimestampNow()} submits directly. */
	@Inject(method = "destroy", at = @At("HEAD"))
	private void ephemera$drainAsyncQueueOnDestroy(CallbackInfo ci) {
		VulkanAsyncPresenter.awaitIdle();
		VulkanAsyncPresenter.resetSubmittedIndex();
	}

	@Inject(method = "getTimestampNow", at = @At("HEAD"))
	private void ephemera$drainAsyncQueueOnTimestamp(CallbackInfoReturnable<Long> cir) {
		VulkanAsyncPresenter.awaitIdle();
	}

	@Inject(method = "destroy", at = @At("TAIL"))
	private void ephemera$freeScratch(CallbackInfo ci) {
		long scratch = ephemera$scratch;
		if (scratch != 0L) {
			ephemera$scratch = 0L;
			MemoryUtil.nmemFree(scratch);
		}
		if (RenderSystem.isOnRenderThread()) {
			VulkanDescriptorPlan.clearRenderThreadCache();
		}
	}

	@Inject(method = "awaitSubmitCompletion", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastSubmitCompletion(long submitIndex, long timeoutNs, CallbackInfoReturnable<Boolean> cir) {
		if (VulkanDiagTracker.ENABLED) {
			ephemera$waitStartNs = System.nanoTime();
		}
		if (completedSubmitIndex >= submitIndex) {
			cir.setReturnValue(true);
			return;
		}
		if (submitIndex >= currentSubmitIndex) {
			// Vanilla: false for zero-timeout polls, throws for blocking waits on the current submit.
			return;
		}
		if (timeoutNs != 0L) {
			// Never wait on a timeline value whose vkQueueSubmit2KHR is still queued on the worker.
			VulkanAsyncPresenter.awaitSubmitted(submitIndex);
			// N-2 has usually retired already: one counter query skips MemoryStack + vkWaitSemaphores.
			long completed = ephemera$querySubmitCounter();
			if (completed > completedSubmitIndex) {
				completedSubmitIndex = completed;
			}
			if (completedSubmitIndex >= submitIndex) {
				if (VulkanDiagTracker.ENABLED) {
					VulkanDiagTracker.recordWaitSem(System.nanoTime() - ephemera$waitStartNs);
				}
				cir.setReturnValue(true);
			}
			return;
		}
		if (!VulkanAsyncPresenter.isSubmitted(submitIndex)) {
			cir.setReturnValue(false);
			return;
		}
		if (!VulkanModernCapabilities.modernSyncActive()) {
			return;
		}
		// Coalesce StagedVertexBuffer$GpuBufferPool's 50-150x/frame zero-timeout fence polls into
		// at most ONE vkGetSemaphoreCounterValue query per submit, eliminating ~6% Render thread JNI overhead.
		if (ephemera$zeroPollSubmitIndex != currentSubmitIndex) {
			long completed = ephemera$querySubmitCounter();
			if (completed < 0L) {
				return;
			}
			if (completed > completedSubmitIndex) {
				completedSubmitIndex = completed;
			}
			ephemera$zeroPollSubmitIndex = currentSubmitIndex;
		}
		cir.setReturnValue(completedSubmitIndex >= submitIndex);
	}

	@Inject(method = "awaitSubmitCompletion", at = @At("RETURN"))
	private void ephemera$diagWaitEnd(long submitIndex, long timeoutNs, CallbackInfoReturnable<Boolean> cir) {
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.recordWaitSem(System.nanoTime() - ephemera$waitStartNs);
		}
	}

	// --- Render pass end: release pooled bindings & defer graphics output barrier ---

	@Inject(method = "submitRenderPass", at = @At("HEAD"))
	private void ephemera$releasePassBindings(CallbackInfo ci) {
		if (currentRenderPass instanceof VulkanRenderPassBridge bridge) {
			bridge.ephemera$releaseBindings();
		}
	}

	@WrapOperation(
		method = "submitRenderPass",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferRenderPassBarrier(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingGraphics = true;
		} else {
			original.call(self, stack);
		}
	}

	// --- Staging uploads (fresh staging source): coalesce disjoint destinations, flush on same-target WAW ---

	@Unique
	private void ephemera$syncBeforeDisjointWrite(long targetHandle) {
		if (ephemera$pendingTransfer) {
			boolean hazard = targetHandle == 0L || ephemera$transferTargetCount >= ephemera$transferTargets.length;
			if (!hazard) {
				for (int i = 0; i < ephemera$transferTargetCount; i++) {
					if (ephemera$transferTargets[i] == targetHandle) {
						hazard = true;
						break;
					}
				}
			}
			if (hazard) {
				ephemera$flushBarriers();
			} else {
				ephemera$flushGraphicsOnly();
			}
		} else {
			ephemera$flushGraphicsOnly();
		}
		if (targetHandle != 0L && ephemera$transferTargetCount < ephemera$transferTargets.length) {
			ephemera$transferTargets[ephemera$transferTargetCount++] = targetHandle;
		}
	}

	@Inject(method = "writeToBuffer", at = @At("HEAD"))
	private void ephemera$syncBeforeWriteBuffer(GpuBufferSlice slice, ByteBuffer data, CallbackInfo ci) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			long handle = slice != null && slice.buffer() instanceof VulkanGpuBuffer vk ? vk.vkBuffer() : 0L;
			ephemera$syncBeforeDisjointWrite(handle);
		}
	}

	@WrapOperation(
		method = "writeToBuffer",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferWriteBufferBarrier(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingTransfer = true;
		} else {
			original.call(self, stack);
		}
	}

	@Inject(method = "writeToTexture", at = @At("HEAD"))
	private void ephemera$syncBeforeWriteTexture(GpuTexture texture, ByteBuffer data, int mip, int depthOrLayer,
												 int x, int y, int width, int height, CallbackInfo ci) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			long handle = texture instanceof VulkanGpuTexture vk ? vk.vkImage() : 0L;
			ephemera$syncBeforeDisjointWrite(handle);
		}
	}

	@WrapOperation(
		method = "writeToTexture",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferWriteTextureBarrier(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$pendingTransfer = true;
		} else {
			original.call(self, stack);
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

	@WrapOperation(
		method = {"copyToBuffer", "copyBufferToTexture", "copyTextureToTexture",
			"clearColorTexture", "clearDepthTexture",
			"clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V"},
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$deferDependentTransferBarrier(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$markUntrackedTransfer();
		} else {
			original.call(self, stack);
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

	@WrapOperation(
		method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$narrowReadbackBarrier(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
		if (VulkanModernCapabilities.modernSyncActive()) {
			ephemera$emitBarrier(commandBuffer(), EPHEMERA$STAGE_TRANSFER, EPHEMERA$ACCESS_TRANSFER_WRITE,
				KHRSynchronization2.VK_PIPELINE_STAGE_2_HOST_BIT_KHR | EPHEMERA$STAGE_TRANSFER | EPHEMERA$STAGE_ALL_GRAPHICS,
				KHRSynchronization2.VK_ACCESS_2_HOST_READ_BIT_KHR | EPHEMERA$ACCESS_ALL_CONSUMERS);
		} else {
			original.call(self, stack);
		}
	}
}
