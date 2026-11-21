package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.math.FastMath;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(Mth.class)
abstract class ChunkModuloMixin {
    @Redirect(method = "positiveModulo(II)I", at = @At(value = "INVOKE", target = "Ljava/lang/Math;floorMod(II)I"))
    private static int ephemera$modulo(int value, int divisor) {
        return EphemeraCommon.config().engineFastChunkLookup ? FastMath.floorMod(value, divisor) : Math.floorMod(value, divisor);
    }
}
