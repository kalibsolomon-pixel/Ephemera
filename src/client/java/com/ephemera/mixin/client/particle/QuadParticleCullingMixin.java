package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleCullingMixin {
    // Replace vanilla's point-only rejection with the interpolated quad envelope below.
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean ephemera$deferPointTest(Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
        return ParticleCullingManager.isCullingEnabled() || original.call(frustum, x, y, z);
    }
    @WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lnet/minecraft/client/Camera;F)V"))
    private boolean ephemera$extract(SingleQuadParticle particle, QuadParticleRenderState state, Camera camera, float tick,
                                     @Local(argsOnly = true) Frustum frustum) {
        return ParticleCullingManager.shouldExtract(particle, frustum, camera, tick);
    }
}
