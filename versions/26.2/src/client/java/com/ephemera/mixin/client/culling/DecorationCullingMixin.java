package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class DecorationCullingMixin {
    @Inject(method = "isEntityVisible", at = @At("RETURN"), cancellable = true)
    private void ephemera$decoration(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && BlockStateCullingManager.decorationHidden(entity)) cir.setReturnValue(false);
    }
}
