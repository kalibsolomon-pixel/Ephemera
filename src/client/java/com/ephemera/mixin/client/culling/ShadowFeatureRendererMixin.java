package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.FastShadowCache;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {
    @Redirect(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/VoxelShape;bounds()Lnet/minecraft/world/phys/AABB;"))
    private AABB ephemera$bounds(VoxelShape shape) { return FastShadowCache.bounds(shape); }
}
