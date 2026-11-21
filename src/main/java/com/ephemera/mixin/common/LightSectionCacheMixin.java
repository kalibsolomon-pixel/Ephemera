package com.ephemera.mixin.common;

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
 * Replaces {@link DataLayerStorageMap}'s linear scan + shift cache with an O(1) 1-entry MRU
 * register plus 16-slot direct-mapped section cache, eliminating linear probing and array
 * shifts during neighbor lighting and chunk meshing queries.
 */
@Mixin(DataLayerStorageMap.class)
abstract class LightSectionCacheMixin {
	@Unique private static final int EPHEMERA$MASK = 15;

	@Shadow private boolean cacheEnabled;
	@Shadow @Final protected Long2ObjectOpenHashMap<DataLayer> map;

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

	@Inject(method = "clearCache", at = @At("TAIL"))
	private void ephemera$clearDirectCache(CallbackInfo ci) {
		this.ephemera$mruKey = Long.MAX_VALUE;
		this.ephemera$mruLayer = null;
		Arrays.fill(this.ephemera$dmKeys, Long.MAX_VALUE);
		Arrays.fill(this.ephemera$dmLayers, null);
	}

	/**
	 * @author Ephemera
	 * @reason O(1) MRU + 16-slot direct-mapped cache replaces linear scan &amp; array shifts.
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
			DataLayer layer = this.map.get(sectionPos);
			if (layer != null) {
				this.ephemera$mruKey = sectionPos;
				this.ephemera$mruLayer = layer;
				this.ephemera$dmKeys[slot] = sectionPos;
				this.ephemera$dmLayers[slot] = layer;
			}
			return layer;
		}
		return this.map.get(sectionPos);
	}
}
