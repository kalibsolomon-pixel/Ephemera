package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
abstract class BlockShapeMixin {
    @Inject(method = "isShapeFullBlock", at = @At("HEAD"), cancellable = true)
    private static void ephemera$knownShape(VoxelShape shape, CallbackInfoReturnable<Boolean> result) {
        if (EphemeraCommon.config().memoryDeduplicateShapes) {
            if (shape == Shapes.block()) result.setReturnValue(true);
            else if (shape == Shapes.empty()) result.setReturnValue(false);
        }
    }
}
