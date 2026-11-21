package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.math.FastMath;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(Mth.class)
abstract class CompactTrigMixin {
    @Redirect(method = {"sin(D)F", "cos(D)F"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/util/Mth;SIN:[F", args = {"array=get", "fuzz=16"}))
    private static float ephemera$sample(float[] vanilla, int phase) {
        return EphemeraCommon.config().engineFastMath ? FastMath.sample(phase) : vanilla[phase];
    }
}
