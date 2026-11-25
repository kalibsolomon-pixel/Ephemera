package com.ephemera.mixin.client.engine;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeltaTracker.Timer.class)
public interface DeltaTrackerTimerAccessor {
    @Accessor("deltaTickResidual") float ephemera$deltaTickResidual();
    @Accessor("lastMs") long ephemera$lastMs();
    @Accessor("msPerTick") float ephemera$msPerTick();
    @Accessor("paused") boolean ephemera$paused();
}
