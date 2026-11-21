package com.ephemera.mixin.client.culling;

import net.minecraft.world.entity.Leashable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Leashable.LeashData.class)
public interface LeashDataAccessor {
    @Accessor("delayedLeashHolderId") int ephemera$delayedHolder();
}
