package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Tutorial.class)
abstract class TutorialIdleMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private TutorialStepInstance instance;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ephemera$skipCompleted(CallbackInfo ci) {
        if (instance != null && instance.getClass() == CompletedTutorialStepInstance.class
                && minecraft.level != null && !minecraft.isDemo() && EphemeraCommon.config().engineIdleShortCircuits) ci.cancel();
    }
}
