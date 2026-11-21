package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.StateInterning;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateMixin {
    @Shadow private VoxelShape occlusionShape;
    @Shadow private VoxelShape[] occlusionShapesByFace;
    @Shadow @Final private static VoxelShape[] EMPTY_OCCLUSION_SHAPES;
    @Shadow @Final private static VoxelShape[] FULL_BLOCK_OCCLUSION_SHAPES;

    @ModifyExpressionValue(method = "initCache", at = @At(value = "NEW",
            target = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache"))
    @Coerce
    private Object ephemera$shareCache(@Coerce Object cache) {
        return EphemeraCommon.config().memoryCompactBlockStates ? StateInterning.cache(cache) : cache;
    }

    @Inject(method = "initCache", at = @At("RETURN"))
    private void ephemera$shareFaces(CallbackInfo ci) {
        var cfg = EphemeraCommon.config();
        if (cfg.memoryDeduplicateShapes) occlusionShape = StateInterning.shape(occlusionShape);
        if (occlusionShapesByFace == EMPTY_OCCLUSION_SHAPES || occlusionShapesByFace == FULL_BLOCK_OCCLUSION_SHAPES) return;
        if (cfg.memoryDeduplicateShapes) {
            for (int i = 0; i < occlusionShapesByFace.length; i++) {
                occlusionShapesByFace[i] = StateInterning.shape(occlusionShapesByFace[i]);
            }
        }
        if (cfg.memoryCompactBlockStates) occlusionShapesByFace = StateInterning.faces(occlusionShapesByFace);
    }
}
