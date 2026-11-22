package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.EntityOcclusionManager;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityOcclusionMixin {
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private void ephemera$occluded(Entity entity, Frustum frustum, double x, double y, double z, float partialTick, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && EntityOcclusionManager.cullEntity(entity,
                ((EntityRenderDispatcher) (Object) this).getRenderer(entity))) cir.setReturnValue(false);
    }
}
