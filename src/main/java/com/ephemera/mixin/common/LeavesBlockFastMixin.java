package com.ephemera.mixin.common;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Eliminates per-tick {@code Direction.values()} array cloning (~54 MB on Server thread during
 * chunk streaming and forest decay) inside {@link LeavesBlock#updateDistance}.
 */
@Mixin(LeavesBlock.class)
abstract class LeavesBlockFastMixin {
	@Unique
	private static final Direction[] EPHEMERA$DIRECTIONS = Direction.values();

	@Redirect(
		method = "updateDistance",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;")
	)
	private static Direction[] ephemera$cachedDirections() {
		return EPHEMERA$DIRECTIONS;
	}
}
