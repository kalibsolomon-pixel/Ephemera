package com.ephemera.mixin.client.particle;

import com.ephemera.render.culling.ParticleCullingManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(Particle.class)
public abstract class ParticleLightmapMixin {
    @Shadow @Final protected ClientLevel level;
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;
    /**
     * @author Ephemera
     * @reason Preserve the vanilla per-block light lookup without allocating BlockPos or callback objects.
     */
    @Overwrite protected int getLightCoords(float tick) { return ParticleCullingManager.light(level, x, y, z); }
}
