package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.GlStateCache;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL30C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;

/**
 * Feeds raw vertex-array / element-array / draw-indirect binds into {@link GlStateCache} so the
 * per-draw bind skips stay coherent with every caller (vanilla routes all binds through
 * {@code GlStateManager}, so a RETURN-side record covers the whole pipeline).
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerBindTrackingMixin {
	@Inject(method = "_glBindVertexArray", at = @At("RETURN"))
	private static void ephemera$trackVaoBind(int vaoId, CallbackInfo ci) {
		GlStateCache.recordVertexArrayBind(vaoId);
	}

	@Inject(method = "_glBindBuffer", at = @At("RETURN"))
	private static void ephemera$trackBufferBind(int target, int buffer, CallbackInfo ci) {
		if (target == GL_ELEMENT_ARRAY_BUFFER) {
			GlStateCache.recordElementArrayBufferBind(buffer);
		} else if (target == GL_DRAW_INDIRECT_BUFFER) {
			GlStateCache.recordDrawIndirectBufferBind(buffer);
		}
	}
}
