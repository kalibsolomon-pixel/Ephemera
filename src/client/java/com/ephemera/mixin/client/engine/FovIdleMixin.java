package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class FovIdleMixin {
    @Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
    private void ephemera$fixedFov(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> ci) {
        if (effectScale == 0 && EphemeraCommon.config().engineIdleShortCircuits) {
            var player = (AbstractClientPlayer) (Object) this;
            // Vanilla's scope zoom is independent of the effects slider.
            if (!firstPerson || !player.isScoping()) ci.setReturnValue(1f);
        }
    }
}
