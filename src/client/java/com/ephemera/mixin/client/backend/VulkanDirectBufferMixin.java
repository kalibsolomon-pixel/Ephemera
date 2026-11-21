package com.ephemera.mixin.client.backend;

import com.ephemera.core.render.BufferViewCache;
import com.ephemera.render.backend.VulkanBufferViews;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.vulkan.VK12.vkDestroyBufferView;

@Mixin(VulkanGpuBuffer.Direct.class)
abstract class VulkanDirectBufferMixin implements VulkanBufferViews {
	@Shadow @Final protected VulkanDevice device;
	@Unique private BufferViewCache ephemera$views;

	@Override
	public BufferViewCache ephemera$bufferViews() {
		if (ephemera$views == null) {
			ephemera$views = new BufferViewCache(16);
		}
		return ephemera$views;
	}

	@Inject(method = "destroy", at = @At("HEAD"))
	private void ephemera$destroyViews(CallbackInfo ci) {
		// close() queues this destroy() after submitted users retire. Views must die before the VkBuffer.
		if (ephemera$views != null) {
			VkDevice nativeDevice = device.vkDevice();
			ephemera$views.destroyAll(view -> vkDestroyBufferView(nativeDevice, view, null));
			ephemera$views = null;
		}
	}
}
