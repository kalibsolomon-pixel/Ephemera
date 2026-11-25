package com.ephemera.mixin.common;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link LegacyRandomSource}'s backing 48-bit LCG seed so render-thread-only loops like
 * {@code ClientLevel.animateTick} can advance the exact LCG in a local register and write back once
 * instead of executing 8,000+ {@code AtomicLong.compareAndSet} barriers per tick.
 */
@Mixin(LegacyRandomSource.class)
public interface LegacyRandomSourceAccessor {
	@Accessor("seed")
	AtomicLong ephemera$seed();
}
