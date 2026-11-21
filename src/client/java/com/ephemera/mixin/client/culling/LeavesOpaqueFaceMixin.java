package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.BlockStateCullingManager;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LeavesBlock.class)
public abstract class LeavesOpaqueFaceMixin {
    @Shadow private static volatile boolean cutoutLeaves;
    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void ephemera$opaque(BlockState state, BlockState neighbor, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (BlockStateCullingManager.opaqueLeafFace(cutoutLeaves, neighbor)) cir.setReturnValue(true);
    }
}
