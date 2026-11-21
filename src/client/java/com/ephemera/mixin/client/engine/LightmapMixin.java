package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.LightmapInputs;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lightmap.class)
abstract class LightmapMixin {
    @Unique private final LightmapInputs ephemera$inputs = new LightmapInputs();

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/Profiler;get()Lnet/minecraft/util/profiling/ProfilerFiller;"), cancellable = true)
    private void ephemera$reuseTexture(LightmapRenderState state, CallbackInfo ci) {
        if (!EphemeraCommon.config().engineLightmapCaching) ephemera$inputs.invalidate();
        else if (ephemera$inputs.matches(state)) ci.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void ephemera$recordUpload(LightmapRenderState state, CallbackInfo ci) {
        if (EphemeraCommon.config().engineLightmapCaching) ephemera$inputs.capture(state);
    }
}
