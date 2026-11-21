package com.ephemera.mixin.client.culling;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.WeatherEffectRenderer$ColumnInstance")
public interface WeatherColumnAccessor {
    @Accessor("x") int ephemera$x();
    @Accessor("z") int ephemera$z();
    @Accessor("bottomY") int ephemera$bottom();
    @Accessor("topY") int ephemera$top();
}
