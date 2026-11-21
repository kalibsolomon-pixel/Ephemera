package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.RenderPassTextureBinding;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanRenderPass$TextureViewAndSampler")
public interface VulkanTextureBindingAccess extends RenderPassTextureBinding.Vulkan {
	@Accessor("view") VulkanGpuTextureView ephemera$view();
	@Accessor("sampler") VulkanGpuSampler ephemera$sampler();
}
