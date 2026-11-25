package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.common.FastChunkLookup;
import com.ephemera.engine.common.FastCubeCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zero-allocation fast paths for {@link CollisionGetter#noBlockCollision} and
 * {@link CollisionGetter#noEntityCollision}, avoiding {@code BlockCollisions} iterator and
 * {@code EntitySectionStorage} list churn across entity ticks.
 */
@Mixin(CollisionGetter.class)
public interface CollisionGetterFastMixin {
	@Inject(
		method = "noBlockCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Z)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ephemera$fastNoBlockCollision(
		@Nullable Entity entity,
		AABB box,
		boolean includeFluids,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (includeFluids || !EphemeraCommon.config().engineFastCollisions) {
			return;
		}
		if (this instanceof Level lvl) {
			int res = FastCubeCollision.tryNoBlockCollision(lvl, entity, box);
			if (res >= 0) {
				cir.setReturnValue(res == 1);
			}
		}
	}

	@Inject(
		method = "noEntityCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ephemera$fastNoEntityCollision(
		@Nullable Entity entity,
		AABB box,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!EphemeraCommon.config().engineFastCollisions) {
			return;
		}
		if (this instanceof FastChunkLookup lookup && !lookup.ephemera$hasCollidableEntities()) {
			cir.setReturnValue(true);
		}
	}
}
