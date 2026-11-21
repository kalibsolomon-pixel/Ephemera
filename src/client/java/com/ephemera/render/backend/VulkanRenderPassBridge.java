package com.ephemera.render.backend;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Duck bridge mixed into {@code VulkanRenderPass} so {@code VK_EXT_mesh_shader} draws can execute
 * directly inside Blaze3D's terrain dynamic rendering pass without opening a second render pass.
 */
public interface VulkanRenderPassBridge {
	VulkanDevice ephemera$device();

	VulkanCommandEncoder ephemera$encoder();

	VkCommandBuffer ephemera$commandBuffer();

	boolean ephemera$hasDepth();

	/** Clears cached pipeline/descriptor state after a custom mesh-shader draw so subsequent Blaze3D draws rebind cleanly. */
	void ephemera$invalidateAfterExternalDraw();
}
