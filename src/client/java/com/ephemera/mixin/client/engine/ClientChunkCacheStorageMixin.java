package com.ephemera.mixin.client.engine;

import com.ephemera.engine.client.ClientChunkStorageFastLookup;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Combines range check, fast non-negative modulo index calculation, array fetch, and coordinate
 * validation on {@code ClientChunkCache$Storage} into one method so {@link ClientChunkCacheFastMixin}
 * performs a single volatile storage load per lookup.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
abstract class ClientChunkCacheStorageMixin implements ClientChunkStorageFastLookup {
	@Shadow @Final private AtomicReferenceArray<LevelChunk> chunks;
	@Shadow @Final private int chunkRadius;
	@Shadow @Final private int viewRange;
	@Shadow private volatile int viewCenterX;
	@Shadow private volatile int viewCenterZ;

	@Override
	public @Nullable LevelChunk ephemera$getChunkIfValid(int chunkX, int chunkZ) {
		int r = this.chunkRadius;
		if (Math.abs(chunkX - this.viewCenterX) > r || Math.abs(chunkZ - this.viewCenterZ) > r) {
			return null;
		}
		int range = this.viewRange;
		int mx = chunkX % range;
		if (mx < 0) {
			mx += range;
		}
		int mz = chunkZ % range;
		if (mz < 0) {
			mz += range;
		}
		LevelChunk chunk = this.chunks.get(mz * range + mx);
		if (chunk != null) {
			ChunkPos pos = chunk.getPos();
			if (pos.x() == chunkX && pos.z() == chunkZ) {
				return chunk;
			}
		}
		return null;
	}
}
