package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.FastShadowCache;
import com.ephemera.render.fastwrite.FastShadowWriter;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {
    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void ephemera$fastShadow(ShadowFeatureRenderer.Submit submit, VertexConsumer consumer, CallbackInfo ci) {
        if (FastShadowWriter.write(submit, consumer)) {
            ci.cancel();
        }
    }

    @Redirect(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/VoxelShape;bounds()Lnet/minecraft/world/phys/AABB;"))
    private AABB ephemera$bounds(VoxelShape shape) { return FastShadowCache.bounds(shape); }
}
