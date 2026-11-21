package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.math.FastMath;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LevelChunkSection.class)
abstract class SectionStateMixin {
    @Redirect(method = {"getBlockState", "getFluidState"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer;get(III)Ljava/lang/Object;"))
    private Object ephemera$getState(PalettedContainer<?> states, int x, int y, int z) {
        if (EphemeraCommon.config().engineFastChunkLookup && states.getClass() == PalettedContainer.class
                && states instanceof PaletteIndexAccessor<?> indexed) {
            return indexed.ephemera$getIndexed(FastMath.sectionIndex(x, y, z));
        }
        return states.get(x, y, z);
    }
}
