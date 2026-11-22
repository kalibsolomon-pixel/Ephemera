package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastParticleQuadWriter;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleRenderState.class)
abstract class QuadParticleFastWriteMixin {
	@Inject(method = "renderRotatedQuad", at = @At("HEAD"), cancellable = true)
	private void ephemera$writeParticle(VertexConsumer consumer, float x, float y, float z, float qx, float qy,
			float qz, float qw, float size, float u0, float u1, float v0, float v1, int color, int light,
			CallbackInfo ci) {
		if (FastParticleQuadWriter.write(consumer, x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, light)) {
			ci.cancel();
		}
	}
}
