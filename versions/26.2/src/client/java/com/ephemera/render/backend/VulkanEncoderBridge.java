package com.ephemera.render.backend;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Duck bridge mixed into {@code VulkanCommandEncoder} so Ephemera's modern Vulkan synchronization
 * and {@code VK_EXT_mesh_shader} terrain engine can record compute dispatches and narrow barriers
 * into the active command buffer cleanly.
 */
public interface VulkanEncoderBridge {
	VulkanDevice ephemera$device();

	VkCommandBuffer ephemera$commandBuffer();

	/** Flushes any deferred transfer or graphics barriers on the current command buffer. */
	void ephemera$flushBarriers();

	/** Marks that a transfer write occurred on the current command buffer (deferred barrier). */
	void ephemera$markTransferWrite();

	/** Emits a narrow barrier from compute shader SSBO/indirect writes to mesh shader + draw indirect reads. */
	void ephemera$barrierComputeToMeshDraw();
}
