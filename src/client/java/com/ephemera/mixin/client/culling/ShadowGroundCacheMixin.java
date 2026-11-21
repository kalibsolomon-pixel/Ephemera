package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.FastShadowCache;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class ShadowGroundCacheMixin {
    @Inject(method = "extractShadowPiece", at = @At("HEAD"), cancellable = true)
    private void ephemera$ground(EntityRenderState state, Level level, float strength,
                                 BlockPos.MutableBlockPos pos, ChunkAccess chunk, CallbackInfo ci) {
        if (FastShadowCache.extract(state, level, strength, pos, chunk)) ci.cancel();
    }
}
