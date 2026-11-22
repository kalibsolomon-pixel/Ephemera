package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.VulkanModernCapabilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanTransientMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes {@code VulkanTransientMemory}:
 * <ul>
 *   <li>On ReBAR / SAM GPUs ({@code useDeviceMemoryForMappedGpuStaging == true}, e.g. RX 9070 XT
 *       and modern RTX), {@code recordGpuMappedCopy} never records any copies, so skips allocating,
 *       beginning, submitting, and ending an empty transient {@code VkCommandBuffer} every frame.</li>
 *   <li>On non-ReBAR GPUs, narrows the trailing staging-copy barrier from
 *       {@code ALL_COMMANDS | MEMORY_READ | MEMORY_WRITE} to a scoped {@code TRANSFER_WRITE}
 *       Synchronization2 barrier.</li>
 * </ul>
 */
@Mixin(VulkanTransientMemory.class)
abstract class VulkanTransientMemoryMixin {
	@Shadow @Final private boolean useDeviceMemoryForMappedGpuStaging;
	@Shadow private long submitIndex;
	@Shadow private boolean anyCommandRecorded;
	@Shadow private VkCommandBuffer commandBuffer;

	@Inject(method = "beginSubmit", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipEmptyTransientCmdBufOnRebar(CallbackInfo ci) {
		if (useDeviceMemoryForMappedGpuStaging && VulkanModernCapabilities.modernSyncActive()) {
			anyCommandRecorded = false;
			commandBuffer = null;
			ci.cancel();
		}
	}

	@Inject(
		method = "endSubmit",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/util/TransientBlockAllocator;rotate()Ljava/lang/Runnable;",
			ordinal = 4,
			shift = At.Shift.AFTER
		),
		cancellable = true
	)
	private void ephemera$skipNullTransientCmdBufOnEndSubmit(CallbackInfo ci) {
		if (commandBuffer == null) {
			submitIndex++;
			ci.cancel();
		}
	}

	@WrapOperation(
		method = "endSubmit",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/system/MemoryStack;)V")
	)
	private void ephemera$narrowTransientBarrier(VkCommandBuffer cmd, MemoryStack stack, Operation<Void> original) {
		if (!VulkanModernCapabilities.modernSyncActive()) {
			original.call(cmd, stack);
			return;
		}
		long dstStage = KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR;
		if (VulkanModernCapabilities.get().meshShaderEnabled()) {
			dstStage |= EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
			if (VulkanModernCapabilities.get().taskShaderEnabled()) {
				dstStage |= EXTMeshShader.VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT;
			}
		}
		long dstAccess = KHRSynchronization2.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_INDEX_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_UNIFORM_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR;
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
			.sType$Default()
			.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR)
			.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR)
			.dstStageMask(dstStage)
			.dstAccessMask(dstAccess);
		VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
			.sType$Default()
			.pMemoryBarriers(barrier);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
	}
}
