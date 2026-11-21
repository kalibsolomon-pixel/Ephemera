package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(DataLayerStorageMap.class)
abstract class LightSectionCacheMixin {
    @Shadow @Final private long[] lastSectionKeys;

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 2), require = 2)
    private int ephemera$capacity(int original) {
        return EphemeraCommon.config().engineFastLighting ? 8 : original;
    }

    @ModifyConstant(method = {"getLayer", "clearCache"}, constant = @Constant(intValue = 2))
    private int ephemera$scanLength(int original) {
        return lastSectionKeys.length;
    }

    @ModifyConstant(method = "getLayer", constant = @Constant(intValue = 1, ordinal = 0))
    private int ephemera$lastSlot(int original) {
        return lastSectionKeys.length - 1;
    }
}
