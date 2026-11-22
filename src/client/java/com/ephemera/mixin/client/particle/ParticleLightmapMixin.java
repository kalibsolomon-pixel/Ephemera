package com.ephemera.mixin.client.particle;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.ParticleCullingManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Particle.class)
public abstract class ParticleLightmapMixin {
    @Shadow @Final protected ClientLevel level;
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;

    @WrapMethod(method = "getLightCoords")
    protected int ephemera$cachedLightCoords(float tick, Operation<Integer> original) {
        return EphemeraCommon.config().particleLightmapCache
            ? ParticleCullingManager.light(level, x, y, z)
            : original.call(tick);
    }
}
