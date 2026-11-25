package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.ClientChunkCacheFastAccess;
import com.ephemera.engine.client.ClientChunkStorageFastLookup;
import com.ephemera.engine.common.EntityClassIndex;
import com.ephemera.engine.common.FastChunkLookup;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exposes O(1) client chunk array lookup and collidable-entity tracking to common physics/fluid
 * fast paths via {@link FastChunkLookup}, and skips per-entity profiler supplier allocations in
 * {@link ClientLevel#tickNonPassenger} when profiling is inactive.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelTickFastMixin implements FastChunkLookup {
	@Shadow @Final private ClientChunkCache chunkSource;
	@Shadow protected abstract void tickPassenger(Entity vehicle, Entity passenger);

	@Unique private int ephemera$collidableEntities;

	@Override
	public @Nullable LevelChunk ephemera$getLoadedChunk(int chunkX, int chunkZ) {
		ClientChunkCache src = this.chunkSource;
		if (src instanceof ClientChunkCacheFastAccess access) {
			ClientChunkStorageFastLookup lookup = access.ephemera$storageLookup();
			if (lookup != null) {
				return lookup.ephemera$getChunkIfValid(chunkX, chunkZ);
			}
		}
		return src.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
	}

	@Override
	public boolean ephemera$hasCollidableEntities() {
		return this.ephemera$collidableEntities > 0;
	}

	@Inject(method = "addEntity", at = @At("RETURN"))
	private void ephemera$onAddEntity(Entity entity, CallbackInfo ci) {
		if (entity != null && EntityClassIndex.canCollideOverride(entity.getClass())) {
			this.ephemera$collidableEntities++;
		}
	}

	@Inject(method = "removeEntity", at = @At("HEAD"))
	private void ephemera$onRemoveEntity(int id, Entity.RemovalReason reason, CallbackInfo ci) {
		if (this.ephemera$collidableEntities > 0) {
			Entity existing = ((ClientLevel) (Object) this).getEntity(id);
			if (existing != null && EntityClassIndex.canCollideOverride(existing.getClass())) {
				this.ephemera$collidableEntities--;
			}
		}
	}

	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastTickNonPassenger(Entity entity, CallbackInfo ci) {
		if (!EphemeraCommon.config().engineFastEntityLookup || Profiler.get() != InactiveProfiler.INSTANCE) {
			return;
		}
		// Avoid allocating entity.typeHolder()::getRegisteredName Supplier + Iterator on empty passengers.
		entity.setOldPosAndRot();
		entity.tickCount++;
		entity.tick();
		var passengers = entity.getPassengers();
		for (int i = 0, n = passengers.size(); i < n; i++) {
			this.tickPassenger(entity, passengers.get(i));
		}
		ci.cancel();
	}
}
