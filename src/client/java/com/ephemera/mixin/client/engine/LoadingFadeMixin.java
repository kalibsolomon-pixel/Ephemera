package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LoadingOverlay.class)
abstract class LoadingFadeMixin {
    @ModifyConstant(method = "extractRenderState", constant = @Constant(floatValue = 1000f))
    private float ephemera$fadeOutDuration(float duration) {
        return EphemeraCommon.config().loadingNonBlockingReload ? 75f : duration;
    }
}
