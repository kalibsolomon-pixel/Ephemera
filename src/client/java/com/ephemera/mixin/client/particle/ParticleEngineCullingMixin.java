package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineCullingMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void ephemera$begin(CallbackInfo ci) { ParticleCullingManager.beginFrame(); }
    @Inject(method = {"extract", "setLevel", "clearParticles"}, at = @At("RETURN"))
    private void ephemera$end(CallbackInfo ci) { ParticleCullingManager.endFrame(); }
}
