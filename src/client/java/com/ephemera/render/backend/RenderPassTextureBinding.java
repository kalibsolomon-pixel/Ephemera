package com.ephemera.render.backend;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

public final class RenderPassTextureBinding {
	private RenderPassTextureBinding() {}

	public interface OpenGl {
		GlTextureView ephemera$view();
		GlSampler ephemera$sampler();
	}

	public interface Vulkan {
		VulkanGpuTextureView ephemera$view();
		VulkanGpuSampler ephemera$sampler();
	}
}
