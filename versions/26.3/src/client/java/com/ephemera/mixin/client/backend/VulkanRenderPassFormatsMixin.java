package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records the dynamic rendering color and depth {@code VkFormat} values on each opened
 * {@code VulkanRenderPass} so {@code VulkanMeshTerrainEngine} can compile matching graphics
 * pipelines directly inside the existing terrain render pass.
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanRenderPassFormatsMixin {
	@Inject(method = "createRenderPass", at = @At("RETURN"))
	private void ephemera$captureAttachmentFormats(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPassBackend> cir) {
		if (!(cir.getReturnValue() instanceof VulkanRenderPassBridge bridge) || descriptor == null) {
			return;
		}
		int colorFmt = 0;
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		if (colors != null && !colors.isEmpty()) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> att0 = colors.get(0);
			if (att0 != null && att0.textureView() != null) {
				colorFmt = VulkanConst.toVk(att0.textureView().texture().getFormat());
			}
		}
		int depthFmt = 0;
		var depth = descriptor.depthAttachment();
		if (depth != null && depth.textureView() != null) {
			depthFmt = VulkanConst.toVk(depth.textureView().texture().getFormat());
		}
		bridge.ephemera$setAttachmentFormats(colorFmt, depthFmt);
	}
}
