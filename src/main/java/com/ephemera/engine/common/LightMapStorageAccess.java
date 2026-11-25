package com.ephemera.engine.common;

import com.ephemera.core.lighting.ShardedCowLongObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Internal bridge attached to {@code DataLayerStorageMap} by {@code LightSectionCacheMixin} so
 * {@code BlockDataLayerStorageMap.copy()} and {@code SkyDataLayerStorageMap.copy()} publish an
 * O(1) 256-byte sharded copy-on-write snapshot instead of cloning a 256 KB–1 MB humongous map.
 */
public interface LightMapStorageAccess {
	/** Shared read-only empty placeholder passed to vanilla's {@code DataLayerStorageMap} constructor. */
	Long2ObjectOpenHashMap<DataLayer> EMPTY_LIGHT_MAP = new Long2ObjectOpenHashMap<>(0);

	ShardedCowLongObjectMap<DataLayer> ephemera$snapshotMap();

	void ephemera$setShardedMap(ShardedCowLongObjectMap<DataLayer> sharded);
}
