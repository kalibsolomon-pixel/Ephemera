package com.ephemera.mixin.client.engine;

import com.ephemera.engine.client.LightmapInputs;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
abstract class LightmapShaderReloadMixin {
    @Inject(method = "apply(Lcom/mojang/renderpearl/api/device/GpuDevice;Lnet/minecraft/client/renderer/ShaderManager$PendingResults;)V", at = @At("RETURN"))
    private void ephemera$invalidateLightmap(CallbackInfo ci) {
        // Also expire captures made before a temporary config toggle.
        LightmapInputs.shadersReloaded();
    }
}
