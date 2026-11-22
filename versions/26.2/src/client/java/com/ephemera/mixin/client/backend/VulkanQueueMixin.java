package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.VulkanAsyncPresenter;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures any in-flight asynchronous {@code vkQueuePresentKHR} has finished before
 * {@code vkQueueWaitIdle} touches the graphics/present queue.
 */
@Mixin(VulkanQueue.class)
abstract class VulkanQueueMixin {
	@Inject(method = "waitIdle", at = @At("HEAD"))
	private void ephemera$awaitAsyncPresentBeforeWaitIdle(CallbackInfo ci) {
		VulkanAsyncPresenter.awaitIdle();
	}
}
