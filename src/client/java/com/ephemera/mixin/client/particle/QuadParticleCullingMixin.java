package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleCullingMixin {
    // Replace vanilla's point-only rejection with the interpolated quad envelope below.
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean ephemera$deferPointTest(Frustum frustum, double x, double y, double z) {
        return com.ephemera.EphemeraCommon.config().particleCulling || frustum.pointInFrustum(x, y, z);
    }
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lnet/minecraft/client/Camera;F)V"))
    private void ephemera$extract(SingleQuadParticle particle, QuadParticleRenderState state, Camera camera, float tick,
                                  Frustum frustum, Camera groupCamera, float groupTick) {
        if (ParticleCullingManager.shouldExtract(particle, frustum, camera, tick)) particle.extract(state, camera, tick);
    }
}
