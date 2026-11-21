package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleRotationMixin {
    @Redirect(method = "extract", at = @At(value = "NEW", target = "org/joml/Quaternionf"))
    private Quaternionf ephemera$rotation() { return ParticleCullingManager.rotation((SingleQuadParticle) (Object) this); }
}
