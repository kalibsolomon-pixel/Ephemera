package com.ephemera.mixin.client.backend;

import com.ephemera.core.render.BufferViewCache;
import com.ephemera.render.backend.VulkanBufferViews;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static org.lwjgl.vulkan.VK12.VK_SUCCESS;

@Mixin(VulkanRenderPass.class)
abstract class VulkanBufferViewMixin {
	@WrapOperation(method = "pushDescriptors", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/VK12;vkCreateBufferView(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkBufferViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int ephemera$reuseBufferView(VkDevice device, VkBufferViewCreateInfo info, VkAllocationCallbacks allocator,
			LongBuffer result, Operation<Integer> original, @Local(index = 7) GpuBufferSlice slice,
			@Share("cachedView") LocalBooleanRef cachedView) {
		cachedView.set(false);
		if (allocator != null || info.pNext() != 0 || info.flags() != 0) {
			return original.call(device, info, allocator, result);
		}
		BufferViewCache views = VulkanBufferViews.cacheFor(slice, info.buffer());
		if (views == null) {
			return original.call(device, info, allocator, result);
		}
		long offset = info.offset();
		long range = info.range();
		int format = info.format();
		long handle = views.find(offset, range, format);
		if (handle != 0) {
			result.put(result.position(), handle);
			cachedView.set(true);
			return VK_SUCCESS;
		}
		int status = original.call(device, info, allocator, result);
		if (status == VK_SUCCESS && !views.isFull()) {
			handle = result.get(result.position());
			if (handle != 0) {
				views.remember(offset, range, format, handle);
				cachedView.set(true);
			}
		}
		return status;
	}

	@WrapOperation(method = "pushDescriptors", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;queueForDestroy(Lcom/mojang/blaze3d/vulkan/Destroyable;)V"))
	private void ephemera$queueUncachedView(VulkanCommandEncoder encoder, Destroyable view, Operation<Void> original,
			@Share("cachedView") LocalBooleanRef cachedView) {
		if (!cachedView.get()) {
			original.call(encoder, view);
		}
	}
}
