package com.ephemera.render.culling;

import com.ephemera.core.culling.SectionOpacityCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * MC glue for {@link SectionOpacityCache}: loads live sections and applies the exact opacity
 * predicate. All slot/word bookkeeping — including the per-word validity gate that clears a
 * recycled slot's stale bytes before they can ever read as known — lives in the core class and
 * is unit-tested there. The predicate must fail open (false = visible) for anything transparent,
 * dynamic, or offset-function shaped.
 */
final class SectionBlockCache {
	static final SectionOpacityCache.Loader<ClientLevel, LevelChunkSection> LOADER =
		new SectionOpacityCache.Loader<ClientLevel, LevelChunkSection>() {
			@Override
			public LevelChunkSection load(ClientLevel level, int sx, int sy, int sz, int x, int y, int z) {
				LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
				return chunk == null ? null : chunk.getSection(level.getSectionIndex(y));
			}

			@Override
			public boolean missingOrAir(ClientLevel level, LevelChunkSection section) {
				return section == null || section.hasOnlyAir();
			}

			@Override
			public boolean isOpaque(ClientLevel level, LevelChunkSection section, int x, int y, int z) {
				BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
				return state.isSolidRender() && state.canOcclude() && !state.getBlock().hasDynamicShape()
					&& !state.hasOffsetFunction();
			}
		};

	private final SectionOpacityCache<ClientLevel, LevelChunkSection> cache =
		new SectionOpacityCache<>(LOADER);

	/** Releases MC chunk-section references at frame end without discarding valid opacity bitsets. */
	void releaseSections() {
		cache.releaseSections();
	}

	/** Full invalidation on terrain revision or world change. */
	void clear() {
		cache.clear();
	}

	/** Targeted invalidation of one 16x16x16 section after a block opacity change. */
	void invalidateSection(int sx, int sy, int sz) {
		cache.invalidateSection(sx, sy, sz);
	}

	/** Targeted invalidation of one chunk column after chunk load or unload. */
	void invalidateColumn(int sx, int sz) {
		cache.invalidateColumn(sx, sz);
	}

	boolean isOpaque(ClientLevel level, int x, int y, int z, long terrainStamp, long frame) {
		if (level.isOutsideBuildHeight(y)) return false;
		return cache.isOpaque(level, terrainStamp, frame, x, y, z);
	}
}
