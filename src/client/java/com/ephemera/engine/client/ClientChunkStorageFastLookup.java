package com.ephemera.engine.client;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Duck interface on {@code ClientChunkCache$Storage} for single-load validated chunk lookup.
 */
public interface ClientChunkStorageFastLookup {
	@Nullable LevelChunk ephemera$getChunkIfValid(int chunkX, int chunkZ);
}
