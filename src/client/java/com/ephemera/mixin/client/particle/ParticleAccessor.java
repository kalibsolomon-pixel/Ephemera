package com.ephemera.mixin.client.particle;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x") double ephemera$x();
    @Accessor("y") double ephemera$y();
    @Accessor("z") double ephemera$z();
    @Accessor("xo") double ephemera$xo();
    @Accessor("yo") double ephemera$yo();
    @Accessor("zo") double ephemera$zo();
}
