package com.ephemera.mixin.common;

import com.ephemera.core.lighting.ShardedCowLongObjectMap;
import com.ephemera.engine.common.LightMapStorageAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Replaces {@link DataLayerStorageMap}'s monolithic {@code Long2ObjectOpenHashMap} (whose
 * {@code clone()} on every {@code swapSectionMap()} allocates 256 KB–1 MB G1 humongous arrays)
 * with a 64-shard lazy copy-on-write {@link ShardedCowLongObjectMap}, paired with an O(1) 1-entry
 * MRU register plus 16-slot direct-mapped section cache.
 */
@Mixin(DataLayerStorageMap.class)
abstract class LightSectionCacheMixin implements LightMapStorageAccess {
	@Unique private static final int EPHEMERA$MASK = 15;

	@Shadow private boolean cacheEnabled;
	@Shadow @Final protected Long2ObjectOpenHashMap<DataLayer> map;

	@Shadow public abstract void clearCache();

	@Unique private ShardedCowLongObjectMap<DataLayer> ephemera$sharded;
	@Unique private long ephemera$mruKey = Long.MAX_VALUE;
	@Unique private @Nullable DataLayer ephemera$mruLayer;
	@Unique private final long[] ephemera$dmKeys = ephemera$newEmptyKeys();
	@Unique private final @Nullable DataLayer[] ephemera$dmLayers = new DataLayer[16];

	@Unique
	private static long[] ephemera$newEmptyKeys() {
		long[] k = new long[16];
		Arrays.fill(k, Long.MAX_VALUE);
		return k;
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$initSharded(Long2ObjectOpenHashMap<DataLayer> initialMap, CallbackInfo ci) {
		if (initialMap == LightMapStorageAccess.EMPTY_LIGHT_MAP) {
			// Populated immediately by Sky/BlockLightMapCopyMixin via ephemera$setShardedMap().
			return;
		}
		ShardedCowLongObjectMap<DataLayer> sharded = new ShardedCowLongObjectMap<>();
		if (initialMap != null && !initialMap.isEmpty()) {
			for (Long2ObjectMap.Entry<DataLayer> entry : initialMap.long2ObjectEntrySet()) {
				sharded.put(entry.getLongKey(), entry.getValue());
			}
			initialMap.clear();
		}
		this.ephemera$sharded = sharded;
	}

	@Override
	public ShardedCowLongObjectMap<DataLayer> ephemera$snapshotMap() {
		ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
		if (s == null) {
			s = new ShardedCowLongObjectMap<>();
			this.ephemera$sharded = s;
		}
		return s.snapshot();
	}

	@Override
	public void ephemera$setShardedMap(ShardedCowLongObjectMap<DataLayer> sharded) {
		this.ephemera$sharded = sharded;
		clearCache();
	}

	@Inject(method = "clearCache", at = @At("TAIL"))
	private void ephemera$clearDirectCache(CallbackInfo ci) {
		this.ephemera$mruKey = Long.MAX_VALUE;
		this.ephemera$mruLayer = null;
		Arrays.fill(this.ephemera$dmKeys, Long.MAX_VALUE);
		Arrays.fill(this.ephemera$dmLayers, null);
	}

	/**
	 * @author Ephemera
	 * @reason Copy-on-write sharded light map avoids cloning the entire world map on swapSectionMap.
	 */
	@Overwrite
	public DataLayer copyDataLayer(long sectionPos) {
		ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
		DataLayer existing = s != null ? s.get(sectionPos) : this.map.get(sectionPos);
		DataLayer copy = existing.copy();
		if (s != null) {
			s.put(sectionPos, copy);
		} else {
			this.map.put(sectionPos, copy);
		}
		clearCache();
		return copy;
	}

	/**
	 * @author Ephemera
	 * @reason Direct lookup on sharded copy-on-write light map.
	 */
	@Overwrite
	public boolean hasLayer(long sectionPos) {
		ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
		return s != null ? s.containsKey(sectionPos) : this.map.containsKey(sectionPos);
	}

	/**
	 * @author Ephemera
	 * @reason O(1) MRU + 16-slot direct-mapped cache backed by sharded copy-on-write light map.
	 */
	@Overwrite
	public @Nullable DataLayer getLayer(long sectionPos) {
		if (this.cacheEnabled) {
			if (sectionPos == this.ephemera$mruKey) {
				return this.ephemera$mruLayer;
			}
			int slot = (int) (sectionPos ^ (sectionPos >>> 20) ^ (sectionPos >>> 40)) & EPHEMERA$MASK;
			if (sectionPos == this.ephemera$dmKeys[slot]) {
				DataLayer hit = this.ephemera$dmLayers[slot];
				this.ephemera$mruKey = sectionPos;
				this.ephemera$mruLayer = hit;
				return hit;
			}
			ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
			DataLayer layer = s != null ? s.get(sectionPos) : this.map.get(sectionPos);
			if (layer != null) {
				this.ephemera$mruKey = sectionPos;
				this.ephemera$mruLayer = layer;
				this.ephemera$dmKeys[slot] = sectionPos;
				this.ephemera$dmLayers[slot] = layer;
			}
			return layer;
		}
		ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
		return s != null ? s.get(sectionPos) : this.map.get(sectionPos);
	}

	/**
	 * @author Ephemera
	 * @reason Copy-on-write sharded removal without cloning untouched shards.
	 */
	@Overwrite
	public @Nullable DataLayer removeLayer(long sectionPos) {
		ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
		DataLayer removed = s != null ? s.remove(sectionPos) : this.map.remove(sectionPos);
		clearCache();
		return removed;
	}

	/**
	 * @author Ephemera
	 * @reason Copy-on-write sharded insertion without cloning untouched shards.
	 */
	@Overwrite
	public void setLayer(long sectionPos, DataLayer layer) {
		ShardedCowLongObjectMap<DataLayer> s = this.ephemera$sharded;
		if (s != null) {
			s.put(sectionPos, layer);
		} else {
			this.map.put(sectionPos, layer);
		}
		clearCache();
	}
}
