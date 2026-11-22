package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 26.2 already rejects hidden cloud faces. Preserve that logic and its required interior faces. */
@Mixin(CloudRenderer.class)
public abstract class CloudFaceCullingMixin {
    @WrapOperation(method = "buildExtrudedCell", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] ephemera$directions(Operation<Direction[]> original) { return BlockStateCullingManager.cloudDirections(); }
}
