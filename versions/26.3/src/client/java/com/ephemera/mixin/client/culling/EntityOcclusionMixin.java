package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.EntityOcclusionManager;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityOcclusionMixin {
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private void ephemera$occluded(Entity entity, Frustum frustum, double x, double y, double z, float partialTick,
                                   CallbackInfoReturnable<Boolean> cir, @Local EntityRenderer<?, ?> renderer) {
        if (cir.getReturnValueZ() && EntityOcclusionManager.cullEntity(entity, renderer)) {
            cir.setReturnValue(false);
        }
    }
}
