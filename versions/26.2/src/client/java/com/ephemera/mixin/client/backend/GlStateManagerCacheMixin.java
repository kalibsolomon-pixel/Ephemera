package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlStateCache;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
abstract class GlStateManagerCacheMixin {
	@Inject(method = "_scissorBox(IIII)V", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glScissor(IIII)V"), cancellable = true)
	private static void ephemera$cacheScissor(int x, int y, int width, int height, CallbackInfo ci) {
		if (!DispatchSettings.glDispatch()) {
			GlStateCache.invalidateScissorBox();
		} else if (!GlStateCache.recordScissorBox(x, y, width, height)) {
			ci.cancel();
		}
	}

	@Inject(method = "_polygonMode(II)V", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glPolygonMode(II)V"), cancellable = true)
	private static void ephemera$cachePolygonMode(int face, int mode, CallbackInfo ci) {
		if (!DispatchSettings.glDispatch()) {
			GlStateCache.invalidatePolygonMode();
		} else if (!GlStateCache.recordPolygonMode(face, mode)) {
			ci.cancel();
		}
	}

	@Inject(method = "_glDeleteBuffers(I)V", at = @At("RETURN"))
	private static void ephemera$invalidateDeletedBuffer(int handle, CallbackInfo ci) {
		GlStateCache.onBufferDeleted(handle);
	}
}
