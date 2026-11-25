package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the unused client-side ambient-sound random roll in {@link Mob#baseTick()}.
 *
 * <p>{@code Mob.playAmbientSound()} routes through {@code Entity.playSound}, which calls
 * {@code Level.playSound(null, ...)}. On {@code ClientLevel}, {@code playSeededSound} with a
 * {@code null} source entity is an unconditional no-op (ambient sounds are played by server
 * packets), so rolling {@code this.random.nextInt(1000)} (an atomic {@code LegacyRandomSource}
 * CAS) on every client-side mob every tick is pure wasted work.
 */
@Mixin(Mob.class)
abstract class MobClientTickFastMixin {
	@WrapOperation(
		method = "baseTick",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Mob;isAlive()Z"
		)
	)
	private boolean ephemera$skipUnusedClientAmbientRoll(Mob self, Operation<Boolean> original) {
		if (EphemeraCommon.config().engineIdleShortCircuits && self.level().isClientSide()) {
			return false;
		}
		return original.call(self);
	}
}
