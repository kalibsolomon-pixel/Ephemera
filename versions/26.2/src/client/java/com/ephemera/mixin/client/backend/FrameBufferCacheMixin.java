package com.ephemera.mixin.client.backend;

import com.ephemera.core.render.FramebufferLookup;
import com.ephemera.render.backend.DispatchSettings;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferAttachment;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FrameBufferCache.class)
abstract class FrameBufferCacheMixin {
	@Unique private final FramebufferLookup ephemera$recentFramebuffers = new FramebufferLookup();

	@WrapMethod(method = "getFbo")
	private int ephemera$findSingleColorFbo(DirectStateAccess access, List<FrameBufferAttachment> colors,
			FrameBufferAttachment depth, Operation<Integer> original) {
		if (!DispatchSettings.glDispatch() || colors.size() != 1) {
			return original.call(access, colors, depth);
		}
		FrameBufferAttachment color = colors.getFirst();
		int colorId = color == null ? 0 : color.glId();
		int colorMip = color == null ? 0 : color.fboMipLevel();
		int depthId = depth == null ? 0 : depth.glId();
		int depthMip = depth == null ? 0 : depth.fboMipLevel();
		if (depth != null && depthId == 0) {
			// Vanilla's key includes the depth attachment's presence even if its GL name is zero.
			return original.call(access, colors, depth);
		}
		int cached = ephemera$recentFramebuffers.find(colorId, colorMip, depthId, depthMip);
		if (cached != 0) {
			return cached;
		}
		int framebuffer = original.call(access, colors, depth);
		ephemera$recentFramebuffers.remember(colorId, colorMip, depthId, depthMip, framebuffer);
		return framebuffer;
	}

	@WrapOperation(method = "destroyFbo", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glDeleteFramebuffers(I)V"))
	private void ephemera$forgetDeletedFbo(int framebuffer, Operation<Void> original) {
		ephemera$recentFramebuffers.remove(framebuffer);
		original.call(framebuffer);
	}
}
