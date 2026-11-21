package com.ephemera.mixin.common;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PalettedContainer.class)
public interface PaletteIndexAccessor<T> {
    @Invoker("get") T ephemera$getIndexed(int index);
}
