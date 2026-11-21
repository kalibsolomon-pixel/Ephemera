package com.ephemera.mixin.client.particle;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraDepthAccessor {
    @Accessor("depthFar") float ephemera$depthFar();
}
