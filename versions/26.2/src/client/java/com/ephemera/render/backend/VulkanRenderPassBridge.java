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

	/** Returns false after an external draw invalidated the currently bound Vulkan pipeline. */
	default boolean ephemera$isPipelineValid() {
		return true;
	}

	/** Monotonic counter bumped on every external Vulkan draw (e.g. mesh shaders) inside this pass. */
	default int ephemera$externalDrawSeq() {
		return 0;
	}

	/** Vulkan {@code VkFormat} of color attachment 0 for this dynamic render pass, or 0 if unavailable. */
	default int ephemera$colorVkFormat() {
		return 0;
	}

	/** Vulkan {@code VkFormat} of the depth attachment for this dynamic render pass, or 0 if unavailable. */
	default int ephemera$depthVkFormat() {
		return 0;
	}

	/** Records dynamic rendering attachment formats when the render pass opens (used on 26.3). */
	default void ephemera$setAttachmentFormats(int colorVkFormat, int depthVkFormat) {}

	/** Returns pooled descriptor binding arrays when the encoder finishes this render pass. */
	void ephemera$releaseBindings();
}
