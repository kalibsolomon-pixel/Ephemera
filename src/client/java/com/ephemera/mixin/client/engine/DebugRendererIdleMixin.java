package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
abstract class DebugRendererIdleMixin {
    @Shadow @Final private List<?> renderers;
    @Shadow private long lastDebugEntriesVersion;

    @Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true)
    private void ephemera$skipEmpty(CallbackInfo ci) {
        if (EphemeraCommon.config().engineIdleShortCircuits && renderers.isEmpty()
                && Minecraft.getInstance().debugEntries.getCurrentlyEnabledVersion() == lastDebugEntriesVersion) ci.cancel();
    }
}
