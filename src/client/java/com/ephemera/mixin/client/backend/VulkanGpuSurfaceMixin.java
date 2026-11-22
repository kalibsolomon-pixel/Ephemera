package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.VulkanAsyncPresenter;
import com.ephemera.render.backend.VulkanDiagTracker;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.List;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Modernizes Vulkan swapchain presentation and blit synchronization:
 * <ul>
 *   <li>Overlaps blocking Windows/WDDM {@code vkQueuePresentKHR} + next-frame
 *       {@code vkAcquireNextImageKHR} (~290 us/frame on RX 9070 XT) with next-frame CPU rendering
 *       on a dedicated {@link VulkanAsyncPresenter} worker thread when VSync is off
 *       ({@code IMMEDIATE} / {@code MAILBOX}), while preserving strict Vulkan external
 *       synchronization.</li>
 *   <li>Waits on {@code acquireSemaphore} at {@code VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR}
 *       instead of {@code ALL_COMMANDS}.</li>
 *   <li>Transitions swapchain image to {@code PRESENT_SRC_KHR} with {@code dstStageMask = NONE}
 *       and replaces the trailing global cache-flushing barrier with a pure execution dependency
 *       ({@code TRANSFER -> COLOR_ATTACHMENT_OUTPUT | TRANSFER}, access masks 0).</li>
 * </ul>
 */
@Mixin(VulkanGpuSurface.class)
abstract class VulkanGpuSurfaceMixin {
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private VkQueue presentQueue;
	@Shadow private long swapchain;
	@Shadow @Final private LongList swapchainImages;
	@Shadow @Final private long[] acquireSemaphores;
	@Shadow private int currentAcquireSemaphore;
	@Shadow private long[] presentSemaphores;
	@Shadow private int currentImageIndex;
	@Shadow private SurfaceException eatenException;
	@Shadow private boolean swapchainSuboptimal;
	@Shadow private boolean swapchainOutOfDate;

	@Unique private long ephemera$t0;
	@Unique private boolean ephemera$uncappedMode;
	@Unique private boolean ephemera$preAcquirePending;
	@Unique private boolean ephemera$deferredAcquire;
	@Unique private boolean ephemera$skipPresent;

	@Inject(method = {"destroySwapchain", "close"}, at = @At("HEAD"))
	private void ephemera$drainAsyncBeforeDestroy(CallbackInfo ci) {
		VulkanAsyncPresenter.awaitAndReset();
		ephemera$preAcquirePending = false;
		ephemera$deferredAcquire = false;
		ephemera$skipPresent = false;
	}

	@Inject(method = "configure", at = @At("RETURN"))
	private void ephemera$noteConfigure(GpuSurface.Configuration config, CallbackInfo ci) {
		GpuSurface.PresentMode mode = config.presentMode();
		ephemera$uncappedMode = mode == GpuSurface.PresentMode.IMMEDIATE || mode == GpuSurface.PresentMode.MAILBOX;
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.notePresentMode(String.valueOf(mode), swapchainImages.size());
		}
	}

	@Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
	private void ephemera$deferAcquireWhenAsyncPresentActive(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) ephemera$t0 = System.nanoTime();
		ephemera$skipPresent = false;
		if (eatenException == null && !swapchainOutOfDate && !swapchainSuboptimal
			&& ephemera$preAcquirePending && VulkanAsyncPresenter.hasResult()) {
			// Image is already being pre-acquired off-thread right behind vkQueuePresentKHR;
			// defer harvesting until blitFromTexture() so CPU frame extraction/rendering runs in parallel!
			ephemera$deferredAcquire = true;
			if (VulkanDiagTracker.ENABLED) {
				VulkanDiagTracker.recordAcquire(System.nanoTime() - ephemera$t0);
			}
			ci.cancel();
			return;
		}
		VulkanAsyncPresenter.awaitAndReset();
		ephemera$preAcquirePending = false;
		ephemera$deferredAcquire = false;
	}

	@Inject(method = "acquireNextTexture", at = @At("RETURN"))
	private void ephemera$diagAcquireEnd(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) VulkanDiagTracker.recordAcquire(System.nanoTime() - ephemera$t0);
	}

	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void ephemera$finishDeferredAcquireBeforeBlit(CommandEncoderBackend encoderBackend,
														  GpuTextureView source, CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) ephemera$t0 = System.nanoTime();
		if (!ephemera$deferredAcquire) {
			VulkanAsyncPresenter.awaitIdle();
			return;
		}
		ephemera$deferredAcquire = false;
		ephemera$preAcquirePending = false;

		VulkanAsyncPresenter.harvest();
		int presRes = VulkanAsyncPresenter.presentResult();
		if (presRes == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
			swapchainSuboptimal = true;
			swapchainOutOfDate = true;
			eatenException = new SurfaceException("Failed to present image, swapchain out of date");
			ephemera$skipPresent = true;
			ci.cancel();
			return;
		}
		if (presRes == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
			swapchainSuboptimal = true;
		} else if (presRes != VK12.VK_SUCCESS) {
			VulkanUtils.crashIfFailure(device, presRes, "Failed to present image");
		}

		if (!VulkanAsyncPresenter.acquired()) {
			ephemera$skipPresent = true;
			ci.cancel();
			return;
		}

		int acqRes = VulkanAsyncPresenter.acquireResult();
		if (acqRes == VK12.VK_TIMEOUT) {
			List<CheckpointExtension.QueueCheckpoints> cps = device.checkpointExtension().retrieveCheckpoints(false);
			throw new IllegalStateException("Timeout acquiring swapchain image: " + VulkanUtils.formatCheckpoints(cps));
		}
		currentImageIndex = VulkanAsyncPresenter.acquiredImageIndex();
		if (acqRes == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
			swapchainSuboptimal = true;
			swapchainOutOfDate = true;
			eatenException = new SurfaceException("Failed to acquire image, swapchain out of date");
			currentImageIndex = -1;
			ephemera$skipPresent = true;
			ci.cancel();
			return;
		}
		if (acqRes == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
			swapchainSuboptimal = true;
		} else {
			VulkanUtils.crashIfFailure(device, acqRes, "Failed to acquire image");
		}
	}

	@Inject(method = "blitFromTexture", at = @At("RETURN"))
	private void ephemera$diagBlitEnd(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) VulkanDiagTracker.recordBlit(System.nanoTime() - ephemera$t0);
	}

	@Inject(method = "present", at = @At("HEAD"), cancellable = true)
	private void ephemera$asyncPresentAndPreAcquire(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) ephemera$t0 = System.nanoTime();
		if (ephemera$skipPresent) {
			ephemera$skipPresent = false;
			currentImageIndex = -1;
			if (VulkanDiagTracker.ENABLED) VulkanDiagTracker.recordPresent(System.nanoTime() - ephemera$t0);
			ci.cancel();
			return;
		}
		if (ephemera$uncappedMode && VulkanAsyncPresenter.isEnabled()
			&& !swapchainOutOfDate && !swapchainSuboptimal
			&& swapchain != 0L && currentImageIndex >= 0 && currentImageIndex < presentSemaphores.length) {
			int imgIdx = currentImageIndex;
			long presentSem = presentSemaphores[imgIdx];
			currentImageIndex = -1;

			int nextAcqIdx = (currentAcquireSemaphore + 1) % acquireSemaphores.length;
			currentAcquireSemaphore = nextAcqIdx;
			long nextAcqSem = acquireSemaphores[nextAcqIdx];

			VulkanAsyncPresenter.dispatch(device.vkDevice(), presentQueue, swapchain, presentSem, imgIdx, true, nextAcqSem);
			ephemera$preAcquirePending = true;
			if (VulkanDiagTracker.ENABLED) VulkanDiagTracker.recordPresent(System.nanoTime() - ephemera$t0);
			ci.cancel();
			return;
		}
		VulkanAsyncPresenter.awaitIdle();
	}

	@Inject(method = "present", at = @At("RETURN"))
	private void ephemera$diagPresentEnd(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) VulkanDiagTracker.recordPresent(System.nanoTime() - ephemera$t0);
	}

	@WrapOperation(
		method = "blitFromTexture",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;waitSemaphore(JJJ)V")
	)
	private void ephemera$narrowAcquireWaitStage(VulkanCommandEncoder encoder, long sem, long value, long stageMask, Operation<Void> original) {
		long narrowed = VulkanModernCapabilities.modernSyncActive()
			? KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR
			: stageMask;
		original.call(encoder, sem, value, narrowed);
	}

	@WrapOperation(
		method = "blitFromTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageMemoryBarrier2$Buffer;dstStageMask(J)Lorg/lwjgl/vulkan/VkImageMemoryBarrier2$Buffer;", ordinal = 1)
	)
	private VkImageMemoryBarrier2.Buffer ephemera$narrowPresentTransitionDstStage(VkImageMemoryBarrier2.Buffer barrier, long dstStage, Operation<VkImageMemoryBarrier2.Buffer> original) {
		return original.call(barrier, VulkanModernCapabilities.modernSyncActive()
			? KHRSynchronization2.VK_PIPELINE_STAGE_2_NONE_KHR
			: dstStage);
	}

	@WrapOperation(
		method = "blitFromTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkMemoryBarrier2$Buffer;srcAccessMask(J)Lorg/lwjgl/vulkan/VkMemoryBarrier2$Buffer;")
	)
	private VkMemoryBarrier2.Buffer ephemera$narrowPostBlitSrcAccess(VkMemoryBarrier2.Buffer barrier, long srcAccess, Operation<VkMemoryBarrier2.Buffer> original) {
		return original.call(barrier, VulkanModernCapabilities.modernSyncActive() ? 0L : srcAccess);
	}

	@WrapOperation(
		method = "blitFromTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkMemoryBarrier2$Buffer;dstStageMask(J)Lorg/lwjgl/vulkan/VkMemoryBarrier2$Buffer;")
	)
	private VkMemoryBarrier2.Buffer ephemera$narrowPostBlitDstStage(VkMemoryBarrier2.Buffer barrier, long dstStage, Operation<VkMemoryBarrier2.Buffer> original) {
		return original.call(barrier, VulkanModernCapabilities.modernSyncActive()
			? (KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
				| KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR)
			: dstStage);
	}

	@WrapOperation(
		method = "blitFromTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkMemoryBarrier2$Buffer;dstAccessMask(J)Lorg/lwjgl/vulkan/VkMemoryBarrier2$Buffer;")
	)
	private VkMemoryBarrier2.Buffer ephemera$narrowPostBlitDstAccess(VkMemoryBarrier2.Buffer barrier, long dstAccess, Operation<VkMemoryBarrier2.Buffer> original) {
		return original.call(barrier, VulkanModernCapabilities.modernSyncActive() ? 0L : dstAccess);
	}
}
