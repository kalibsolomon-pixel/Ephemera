package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VMP-style entity-tracking fast path: short-circuits {@code ChunkMap$TrackedEntity#getEffectiveRange()}
 * when the tracked entity has no passengers ({@code !entity.isVehicle()}, true for &gt;99.9% of
 * entities), eliminating {@code Entity.getIndirectPassengersStream()} Stream pipeline allocations
 * on every entity × player tracking update.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityFastMixin {
	@Shadow @Final Entity entity;
	@Shadow @Final private int range;

	@Shadow
	private native int scaledRange(int range);

	@Inject(method = "getEffectiveRange()I", at = @At("HEAD"), cancellable = true)
	private void ephemera$noPassengerFastRange(CallbackInfoReturnable<Integer> cir) {
		if (EphemeraCommon.config().engineFastEntityLookup && !this.entity.isVehicle()) {
			cir.setReturnValue(this.scaledRange(this.range));
		}
	}
}
