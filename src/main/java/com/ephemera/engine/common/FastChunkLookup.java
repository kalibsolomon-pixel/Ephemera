package com.ephemera.engine.common;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Common hook implemented by {@code ClientLevel} (and optionally other levels) so shared
 * common-side fast paths can bypass generic {@code Level.getChunk} dispatch and skip
 * empty entity-collision queries without importing client-only types.
 */
public interface FastChunkLookup {
	@Nullable LevelChunk ephemera$getLoadedChunk(int chunkX, int chunkZ);

	default boolean ephemera$hasCollidableEntities() {
		return true;
	}
}
