package com.ephemera.mixin.client.particle;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Frustum.class)
public interface FrustumAccessor {
    @Invoker("cubeInFrustum") int ephemera$cube(double x0, double y0, double z0, double x1, double y1, double z1);
}
