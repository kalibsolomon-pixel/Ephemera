package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleRotationMixin {
    @WrapOperation(method = "extract", at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;"))
    private Quaternionf ephemera$rotation(Operation<Quaternionf> original) {
        Quaternionf reused = ParticleCullingManager.tryReuseRotation((SingleQuadParticle) (Object) this);
        return reused != null ? reused : original.call();
    }
}
