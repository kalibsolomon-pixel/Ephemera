package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LightEngine.class)
abstract class LightChunkCacheMixin {
    @Shadow @Final private long[] lastChunkPos;

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 2), require = 2)
    private int ephemera$capacity(int original) {
        return EphemeraCommon.config().engineFastLighting ? 8 : original;
    }

    @ModifyConstant(method = {"getChunk"}, constant = @Constant(intValue = 2))
    private int ephemera$scanLength(int original) {
        return lastChunkPos.length;
    }

    @ModifyConstant(method = "getChunk", constant = @Constant(intValue = 1, ordinal = 0))
    private int ephemera$lastSlot(int original) {
        return lastChunkPos.length - 1;
    }
}
