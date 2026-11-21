package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
abstract class TitleFadeMixin {
    @Shadow private boolean fading;

    @Inject(method = "<init>(ZLnet/minecraft/client/gui/components/LogoRenderer;)V", at = @At("RETURN"))
    private void ephemera$skipFade(CallbackInfo ci) {
        if (EphemeraCommon.config().loadingSkipTitleFade) fading = false;
    }
}
