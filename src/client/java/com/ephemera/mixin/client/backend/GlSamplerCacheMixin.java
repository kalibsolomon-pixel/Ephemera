package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.GlStateCache;
import com.mojang.blaze3d.opengl.GlSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlSampler.class)
abstract class GlSamplerCacheMixin {
	@Shadow @Final private int id;

	@Inject(method = "close()V", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glDeleteSamplers(I)V", shift = At.Shift.AFTER))
	private void ephemera$invalidateDeletedSampler(CallbackInfo ci) {
		GlStateCache.onSamplerDeleted(id);
	}
}
