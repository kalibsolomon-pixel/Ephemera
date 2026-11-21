package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineCullingMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void ephemera$begin(ParticlesRenderState state, Frustum frustum, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleCullingManager.beginFrame(camera);
    }
    @Inject(method = {"extract", "setLevel", "clearParticles"}, at = @At("RETURN"))
    private void ephemera$end(CallbackInfo ci) { ParticleCullingManager.endFrame(); }
}
