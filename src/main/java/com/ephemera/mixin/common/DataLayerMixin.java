package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.*;

@Mixin(DataLayer.class)
abstract class DataLayerMixin {
    @Shadow private byte[] data;
    @Shadow private int defaultValue;

    @WrapMethod(method = "get(I)I")
    private int ephemera$readNibble(int index, Operation<Integer> original) {
        if (!EphemeraCommon.config().engineFastLighting) return original.call(index);
        byte[] bytes = data;
        if (bytes == null) return defaultValue;
        return (bytes[index >> 1] >>> ((index & 1) << 2)) & 15;
    }
}
