package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
abstract class LevelChunkIndexMixin {
    @Unique private boolean ephemera$fixedHeight;
    @Unique private int ephemera$minSection;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ephemera$captureHeight(CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getClass() == LevelChunk.class && EphemeraCommon.config().engineFastChunkLookup) {
            ephemera$minSection = chunk.getMinSectionY();
            ephemera$fixedHeight = true;
        }
    }

    @WrapOperation(method = {"getBlockState", "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;", "setBlockState"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionIndex(I)I"))
    private int ephemera$sectionIndex(LevelChunk chunk, int y, Operation<Integer> original) {
        return ephemera$fixedHeight ? (y >> 4) - ephemera$minSection : original.call(chunk, y);
    }
}
