package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherCullingMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private boolean ephemera$column(List columns, Object column) {
        WeatherColumnAccessor c = (WeatherColumnAccessor) column;
        return !BlockStateCullingManager.weatherHidden(c.ephemera$x(), c.ephemera$bottom(), c.ephemera$top(), c.ephemera$z()) && columns.add(column);
    }
}
