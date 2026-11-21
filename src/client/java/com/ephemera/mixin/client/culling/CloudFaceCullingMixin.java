package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 26.2 already rejects hidden cloud faces. Preserve that logic and its required interior faces. */
@Mixin(CloudRenderer.class)
public abstract class CloudFaceCullingMixin {
    @Redirect(method = "buildExtrudedCell", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] ephemera$directions() { return BlockStateCullingManager.cloudDirections(); }
}
