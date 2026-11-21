package com.ephemera.mixin.common;

import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.PaletteResize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a 1-entry MRU identity cache to {@link LinearPalette#idFor} for runs of identical
 * block/biome writes during worldgen and fluid fills, and hoists array/size fields out of the loop.
 */
@Mixin(LinearPalette.class)
abstract class LinearPaletteFastMixin<T> {
	@Shadow @Final private T[] values;
	@Shadow @Final private int bits;
	@Shadow private int size;

	@Unique private T ephemera$lastValue;
	@Unique private int ephemera$lastId = -1;

	@Inject(method = "read", at = @At("HEAD"))
	private void ephemera$invalidateCache(FriendlyByteBuf buf, IdMap<T> registry, CallbackInfo ci) {
		this.ephemera$lastValue = null;
		this.ephemera$lastId = -1;
	}

	/**
	 * @author Ephemera
	 * @reason 1-entry MRU identity cache + hoisted locals for worldgen palette writes.
	 */
	@Overwrite
	public int idFor(T value, PaletteResize<T> resizeHandler) {
		int cached = this.ephemera$lastId;
		if (cached >= 0 && this.ephemera$lastValue == value) {
			return cached;
		}
		T[] vals = this.values;
		int n = this.size;
		for (int i = 0; i < n; i++) {
			if (vals[i] == value) {
				this.ephemera$lastValue = value;
				this.ephemera$lastId = i;
				return i;
			}
		}
		if (n < vals.length) {
			vals[n] = value;
			this.size = n + 1;
			this.ephemera$lastValue = value;
			this.ephemera$lastId = n;
			return n;
		}
		return resizeHandler.onResize(this.bits + 1, value);
	}

	/**
	 * @author Ephemera
	 * @reason Single unsigned bounds check in palette lookup.
	 */
	@Overwrite
	public T valueFor(int id) {
		if (Integer.compareUnsigned(id, this.size) < 0) {
			return this.values[id];
		}
		throw new MissingPaletteEntryException(id);
	}
}
