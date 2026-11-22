package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.math.FastMath;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Mth.class)
abstract class ChunkModuloMixin {
    @WrapOperation(method = "positiveModulo(II)I", at = @At(value = "INVOKE", target = "Ljava/lang/Math;floorMod(II)I"))
    private static int ephemera$modulo(int value, int divisor, Operation<Integer> original) {
        return EphemeraCommon.config().engineFastChunkLookup ? FastMath.floorMod(value, divisor) : original.call(value, divisor);
    }
}
