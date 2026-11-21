package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBlockRenderer.class)
public abstract class LeafBlockCullingMixin {
    @Shadow @Final private boolean cull;
    @Inject(method = "tesselateBlock", at = @At("HEAD"), cancellable = true)
    private void ephemera$enclosedLeaf(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter world,
                                       BlockPos pos, BlockState state, BlockStateModel model, long seed, CallbackInfo ci) {
        if (cull && BlockStateCullingManager.enclosedLeaf(world, pos, state)) ci.cancel();
    }
}
