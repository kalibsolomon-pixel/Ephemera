package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.EntityOcclusionManager;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityOcclusionMixin {
    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private void ephemera$occluded(BlockEntity entity, float tick, ModelFeatureRenderer.CrumblingOverlay overlay,
                                   boolean global, CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (EntityOcclusionManager.cullBlockEntity(entity, global,
                ((BlockEntityRenderDispatcher) (Object) this).getRenderer(entity))) cir.setReturnValue(null);
    }
}
