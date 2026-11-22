package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import java.util.Arrays;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VMP/Lithium-style 16-slot direct-mapped server-thread chunk lookup cache mirroring
 * {@link ServerChunkCache}'s 4-entry linear array. Eliminates thrashing on multi-chunk server
 * ticks (mob spawning, block/fluid storms, worldgen frontier, and multi-player chunk traffic)
 * while staying 100% synchronized via {@code storeInCache} and {@code clearCache}.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheFastMixin {
	@Unique private static final int EPHEMERA$MASK = 15;

	@Shadow @Final Thread mainThread;

	@Unique private final long[] ephemera$dmPos = ephemera$newPosArray();
	@Unique private final ChunkStatus[] ephemera$dmStatus = new ChunkStatus[16];
	@Unique private final ChunkAccess[] ephemera$dmChunk = new ChunkAccess[16];

	@Unique
	private static long[] ephemera$newPosArray() {
		long[] a = new long[16];
		Arrays.fill(a, ChunkPos.INVALID_CHUNK_POS);
		return a;
	}

	@Unique
	private static int ephemera$slot(int x, int z) {
		return ((x * 31) ^ (z * 17)) & EPHEMERA$MASK;
	}

	@Unique
	private static int ephemera$slot(long packed) {
		return (((int) packed * 31) ^ ((int) (packed >>> 32) * 17)) & EPHEMERA$MASK;
	}

	@Inject(method = "clearCache()V", at = @At("TAIL"))
	private void ephemera$clearDirectCache(CallbackInfo ci) {
		Arrays.fill(this.ephemera$dmPos, ChunkPos.INVALID_CHUNK_POS);
		Arrays.fill(this.ephemera$dmStatus, null);
		Arrays.fill(this.ephemera$dmChunk, null);
	}

	@Inject(
		method = "storeInCache(JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)V",
		at = @At("TAIL")
	)
	private void ephemera$mirrorStore(long pos, @Nullable ChunkAccess chunk, ChunkStatus status, CallbackInfo ci) {
		int slot = ephemera$slot(pos);
		this.ephemera$dmPos[slot] = pos;
		this.ephemera$dmStatus[slot] = status;
		this.ephemera$dmChunk[slot] = chunk;
	}

	@Inject(
		method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ephemera$fastGetChunk(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<@Nullable ChunkAccess> cir) {
		if (!EphemeraCommon.config().engineFastChunkLookup || Thread.currentThread() != this.mainThread) {
			return;
		}
		int slot = ephemera$slot(x, z);
		long key = ChunkPos.pack(x, z);
		if (this.ephemera$dmPos[slot] == key && this.ephemera$dmStatus[slot] == status) {
			ChunkAccess chunk = this.ephemera$dmChunk[slot];
			if (chunk != null || !load) {
				cir.setReturnValue(chunk);
			}
		}
	}

	@Inject(
		method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ephemera$fastGetChunkNow(int x, int z, CallbackInfoReturnable<@Nullable LevelChunk> cir) {
		if (!EphemeraCommon.config().engineFastChunkLookup || Thread.currentThread() != this.mainThread) {
			return;
		}
		int slot = ephemera$slot(x, z);
		long key = ChunkPos.pack(x, z);
		if (this.ephemera$dmPos[slot] == key && this.ephemera$dmStatus[slot] == ChunkStatus.FULL) {
			ChunkAccess chunk = this.ephemera$dmChunk[slot];
			cir.setReturnValue(chunk instanceof LevelChunk lc ? lc : null);
		}
	}
}
