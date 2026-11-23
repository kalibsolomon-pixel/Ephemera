package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.ClientChunkCacheFastAccess;
import com.ephemera.engine.client.ClientChunkStorageFastLookup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Eliminates two redundant {@code volatile} reads of {@code ClientChunkCache.storage} (and the
 * view-radius-swap TOCTOU window) on every client chunk lookup by mirroring the active storage
 * reference through {@link ClientChunkStorageFastLookup}.
 */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheFastMixin implements ClientChunkCacheFastAccess {
	@Shadow @Final private LevelChunk emptyChunk;
	@Unique private volatile @Nullable ClientChunkStorageFastLookup ephemera$storage;

	@Override
	public @Nullable ClientChunkStorageFastLookup ephemera$storageLookup() {
		return this.ephemera$storage;
	}

	@WrapOperation(
		method = {"<init>", "updateViewRadius"},
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;",
			opcode = Opcodes.PUTFIELD
		)
	)
	private void ephemera$mirrorStorage(
		ClientChunkCache instance,
		@Coerce Object value,
		Operation<Void> original
	) {
		original.call(instance, value);
		this.ephemera$storage = (value instanceof ClientChunkStorageFastLookup lookup) ? lookup : null;
	}

	@WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;")
	private @Nullable LevelChunk ephemera$fastGetChunk(
		int x,
		int z,
		ChunkStatus status,
		boolean load,
		Operation<LevelChunk> original
	) {
		if (!EphemeraCommon.config().engineFastChunkLookup) {
			return original.call(x, z, status, load);
		}
		ClientChunkStorageFastLookup lookup = this.ephemera$storage;
		if (lookup != null) {
			LevelChunk chunk = lookup.ephemera$getChunkIfValid(x, z);
			if (chunk != null) {
				return chunk;
			}
			return load ? this.emptyChunk : null;
		}
		return original.call(x, z, status, load);
	}
}
