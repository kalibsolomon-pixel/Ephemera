package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelLoadTracker.class)
abstract class LevelLoadDelayMixin {
    @ModifyExpressionValue(method = "isLevelReady", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;closeDelayMs:J"))
    private long ephemera$readyDelay(long delay) {
        return delay == 500 && EphemeraCommon.config().loadingSkipLevelLoadDelay
                && Minecraft.getInstance().hasSingleplayerServer() ? 0 : delay;
    }
}
