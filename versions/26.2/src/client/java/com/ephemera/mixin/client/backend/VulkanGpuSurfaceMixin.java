package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.pacing.CapturePacingPolicy;
import com.ephemera.platform.BorderlessFlipWindow;
import com.ephemera.platform.Win32DisplayRuntime;
import com.ephemera.render.backend.VulkanAsyncPresenter;
import com.ephemera.render.pacing.CaptureOptimizationService;
import com.ephemera.render.backend.VulkanDiagTracker;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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
import java.nio.LongBuffer;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTFullScreenExclusive;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFullScreenExclusiveInfoEXT;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
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
	@Shadow @Final private Set<GpuSurface.PresentMode> supportedPresentModes;

	@Unique private long ephemera$t0;
	@Unique private boolean ephemera$uncappedMode;
	@Unique private boolean ephemera$preAcquirePending;
	@Unique private boolean ephemera$deferredAcquire;
	@Unique private boolean ephemera$skipPresent;
	@Unique private int ephemera$lastSurfaceMinImages;
	@Unique private int ephemera$lastSurfaceMaxImages;
	@Unique private int ephemera$lastVanillaRequestedImages;
	@Unique private int ephemera$lastRequestedImageCount;
	@Unique private boolean ephemera$lastCaptureActive;

	/**
	 * Vanilla allocates only 2 binary acquire semaphores, which is tight when 2 GPU frames are in
	 * flight plus 1 pre-acquired next-frame image and up to 4 swapchain images in borderless or
	 * capture mode. Expanding to 6 eliminates any binary-semaphore reuse hazard; {@code <init>}
	 * and {@code close()} already iterate over {@code acquireSemaphores.length}.
	 */
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 2, ordinal = 0))
	private int ephemera$expandAcquireSemaphoreRing(int vanillaCount) {
		return 6;
	}

	// Swapchain destroy / close on the render thread drains every queued submit + present job first,
	// and consumes any already-signaled pre-acquired binary semaphore so it is never re-passed to
	// vkAcquireNextImageKHR while still signaled (VUID-vkAcquireNextImageKHR-semaphore-01286/-01779).
	@Inject(method = {"destroySwapchain", "close"}, at = @At("HEAD"))
	private void ephemera$drainAsyncBeforeDestroy(CallbackInfo ci) {
		boolean consumeAcquire = false;
		if (ephemera$preAcquirePending) {
			VulkanAsyncPresenter.harvest();
			int acqRes = VulkanAsyncPresenter.acquireResult();
			consumeAcquire = VulkanAsyncPresenter.acquired()
				&& (acqRes == VK12.VK_SUCCESS || acqRes == KHRSwapchain.VK_SUBOPTIMAL_KHR);
		}
		VulkanAsyncPresenter.awaitAndReset();
		if (consumeAcquire && currentAcquireSemaphore >= 0 && currentAcquireSemaphore < acquireSemaphores.length) {
			long sem = acquireSemaphores[currentAcquireSemaphore];
			if (sem != 0L) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack)
						.sType$Default()
						.semaphore(sem)
						.value(0L)
						.stageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR);
					VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack)
						.sType$Default()
						.pWaitSemaphoreInfos(waitInfo);
					KHRSynchronization2.vkQueueSubmit2KHR(device.graphicsQueue().vkQueue(), submitInfo, 0L);
				}
			}
		}
		ephemera$preAcquirePending = false;
		ephemera$deferredAcquire = false;
		ephemera$skipPresent = false;
		ephemera$lastRequestedImageCount = 0;
		VulkanAsyncPresenter.setSurfaceActive(false, false);
	}

	/**
	 * Uncapped present modes in borderless fullscreen or under a capture hook: the compositor or
	 * capture layer also holds the just-presented image, so a fourth image keeps
	 * {@code vkAcquireNextImageKHR} from stalling. FIFO keeps vanilla's count (no added latency).
	 */
	@WrapOperation(method = "configure", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;minImageCount(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
	private VkSwapchainCreateInfoKHR ephemera$swapchainHeadroom(VkSwapchainCreateInfoKHR info, int count,
			Operation<VkSwapchainCreateInfoKHR> original, @Local(argsOnly = true) GpuSurface.Configuration config,
			@Local VkSurfaceCapabilitiesKHR caps) {
		GpuSurface.PresentMode mode = config.presentMode();
		boolean uncapped = mode == GpuSurface.PresentMode.IMMEDIATE || mode == GpuSurface.PresentMode.MAILBOX;
		boolean captureActive = CaptureOptimizationService.isCaptureActive();
		int desired = CapturePacingPolicy.preferredVulkanSwapchainImages(caps.minImageCount(),
			caps.maxImageCount(), count, uncapped, Win32DisplayRuntime.isBorderlessActive(), captureActive);
		ephemera$lastSurfaceMinImages = caps.minImageCount();
		ephemera$lastSurfaceMaxImages = caps.maxImageCount();
		ephemera$lastVanillaRequestedImages = count;
		ephemera$lastRequestedImageCount = desired;
		ephemera$lastCaptureActive = captureActive;
		return original.call(info, desired);
	}

	/**
	 * Chains {@code VK_FULL_SCREEN_EXCLUSIVE_DISALLOWED_EXT} when creating a non-exclusive swapchain
	 * on Windows so exact-monitor-size borderless windows qualify for Windows DWM Hardware
	 * Independent Flip without ever triggering legacy Fullscreen Exclusive black-screen switches
	 * or capture dropouts on Alt-Tab.
	 */
	@WrapOperation(
		method = "configure",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
		)
	)
	private int ephemera$disallowImplicitFseInBorderless(VkDevice vkDevice,
														 VkSwapchainCreateInfoKHR createInfo,
														 VkAllocationCallbacks allocator,
														 LongBuffer pSwapchain,
														 Operation<Integer> original) {
		boolean optimizeFlip = EphemeraCommon.config().borderlessFlipOptimization;
		boolean exclusive = ephemera$isExclusiveFullscreen();
		boolean disallowFse = Win32DisplayRuntime.IS_WINDOWS
			&& optimizeFlip
			&& !exclusive
			&& VulkanModernCapabilities.get().fullScreenExclusiveEnabled();
		long prevPNext = createInfo.pNext();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (disallowFse) {
				VkSurfaceFullScreenExclusiveInfoEXT fseInfo = VkSurfaceFullScreenExclusiveInfoEXT.calloc(stack)
					.sType$Default()
					.pNext(prevPNext)
					.fullScreenExclusive(EXTFullScreenExclusive.VK_FULL_SCREEN_EXCLUSIVE_DISALLOWED_EXT);
				createInfo.pNext(fseInfo.address());
			}
			int res = original.call(vkDevice, createInfo, allocator, pSwapchain);
			if (res == VK12.VK_SUCCESS) {
				VulkanModernCapabilities.LOGGER.info(
					"Vulkan swapchain configured: {}x{}, minImages={}, presentMode={}, fseDisallowed={}, borderless={}, capture={}",
					createInfo.imageExtent().width(), createInfo.imageExtent().height(),
					createInfo.minImageCount(), createInfo.presentMode(), disallowFse,
					Win32DisplayRuntime.isBorderlessActive(), CaptureOptimizationService.isCaptureActive()
				);
			}
			return res;
		} finally {
			createInfo.pNext(prevPNext);
		}
	}

	@Unique
	private static boolean ephemera$isExclusiveFullscreen() {
		if (Win32DisplayRuntime.isBorderlessActive()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc != null && (Object) mc.getWindow() instanceof BorderlessFlipWindow window
			&& window.ephemera$isExclusiveFullscreen();
	}

	@Inject(method = "configure", at = @At("RETURN"))
	private void ephemera$noteConfigure(GpuSurface.Configuration config, CallbackInfo ci) {
		GpuSurface.PresentMode mode = config.presentMode();
		ephemera$uncappedMode = mode == GpuSurface.PresentMode.IMMEDIATE || mode == GpuSurface.PresentMode.MAILBOX;
		VulkanAsyncPresenter.setSurfaceActive(ephemera$uncappedMode, swapchain != 0L && !swapchainOutOfDate);
		if (VulkanDiagTracker.ENABLED) {
			VulkanDiagTracker.notePresentMode(String.valueOf(mode), swapchainImages.size(), "supported=" + supportedPresentModes);
		}
	}

	@Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
	private void ephemera$deferAcquireWhenAsyncPresentActive(CallbackInfo ci) {
		if (VulkanDiagTracker.ENABLED) ephemera$t0 = System.nanoTime();
		ephemera$skipPresent = false;
		// Coalesce capture transitions on the render thread and reconfigure only if desired image count changes.
		boolean captureNow = CaptureOptimizationService.isCaptureActive();
		if (captureNow != ephemera$lastCaptureActive && ephemera$lastSurfaceMinImages > 0 && swapchain != 0L && !swapchainOutOfDate) {
			ephemera$lastCaptureActive = captureNow;
			int desired = CapturePacingPolicy.preferredVulkanSwapchainImages(
				ephemera$lastSurfaceMinImages, ephemera$lastSurfaceMaxImages,
				ephemera$lastVanillaRequestedImages, ephemera$uncappedMode,
				Win32DisplayRuntime.isBorderlessActive(), captureNow);
			if (desired != ephemera$lastRequestedImageCount) {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null) {
					mc.invalidateSurfaceConfiguration();
				}
			}
		}
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
			// blitFromTexture only records a transient command buffer and appends to the encoder's
			// submission; it never touches VkQueue / VkSwapchainKHR, so queued jobs may keep running.
			return;
		}
		ephemera$deferredAcquire = false;
		ephemera$preAcquirePending = false;

		// Waits only for the queued present + pre-acquire, not for any later queued submit.
		VulkanAsyncPresenter.harvest();
		int presRes = VulkanAsyncPresenter.presentResult();
		if (presRes == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
			swapchainSuboptimal = true;
			swapchainOutOfDate = true;
			VulkanAsyncPresenter.setSurfaceActive(false, false);
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
			VulkanAsyncPresenter.setSurfaceActive(false, false);
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

			// Non-blocking: queued FIFO right behind this frame's vkQueueSubmit2KHR job.
			VulkanAsyncPresenter.dispatch(device.vkDevice(), presentQueue, swapchain, presentSem, imgIdx, true, nextAcqSem);
			ephemera$preAcquirePending = true;
			if (VulkanDiagTracker.ENABLED) VulkanDiagTracker.recordPresent(System.nanoTime() - ephemera$t0);
			ci.cancel();
			return;
		}
		// VSync / fallback: vanilla vkQueuePresentKHR runs here, so drain queued submits first.
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
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageMemoryBarrier2$Buffer;srcStageMask(J)Lorg/lwjgl/vulkan/VkImageMemoryBarrier2$Buffer;", ordinal = 0)
	)
	private VkImageMemoryBarrier2.Buffer ephemera$chainAcquireTransitionSrcStage(VkImageMemoryBarrier2.Buffer barrier, long srcStage, Operation<VkImageMemoryBarrier2.Buffer> original) {
		// Chains the UNDEFINED -> TRANSFER_DST layout transition after our TRANSFER acquire-semaphore wait.
		return original.call(barrier, VulkanModernCapabilities.modernSyncActive()
			? KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR
			: srcStage);
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
