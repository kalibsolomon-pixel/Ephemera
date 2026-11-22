package com.ephemera.mixin.common;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Devirtualizes the megamorphic {@code Palette.valueFor} and bimorphic {@code BitStorage.get}
 * interface call sites in {@link PalettedContainer#get(int)} and avoids redundant volatile
 * {@code this.data} re-reads in {@link PalettedContainer#getAndSet(int, Object)}.
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerFastMixin<T> {
	@Shadow private volatile PalettedContainer.Data<T> data;

	/**
	 * @author Ephemera
	 * @reason Monomorphic type-guarded fast paths avoid megamorphic Palette/BitStorage itable dispatch.
	 */
	@Overwrite
	public T get(int index) {
		PalettedContainer.Data<T> d = this.data;
		Palette<T> pal = d.palette();
		if (pal instanceof SingleValuePalette<T> svp) {
			return svp.valueFor(0);
		}
		BitStorage stor = d.storage();
		int id;
		if (stor instanceof SimpleBitStorage sbs) {
			id = sbs.get(index);
		} else if (stor instanceof ZeroBitStorage) {
			id = 0;
		} else {
			id = stor.get(index);
		}
		if (pal instanceof LinearPalette<T> lp) {
			return lp.valueFor(id);
		}
		return pal.valueFor(id);
	}

	/**
	 * @author Ephemera
	 * @reason Avoids redundant 3rd volatile data load and devirtualizes common SimpleBitStorage/LinearPalette writes.
	 */
	@Overwrite
	public T getAndSet(int index, T value) {
		@SuppressWarnings("unchecked")
		PalettedContainer<T> self = (PalettedContainer<T>) (Object) this;
		int id = this.data.palette().idFor(value, self);
		// Re-read once after idFor in case onResize swapped this.data.
		PalettedContainer.Data<T> d = this.data;
		BitStorage stor = d.storage();
		int prevId = (stor instanceof SimpleBitStorage sbs) ? sbs.getAndSet(index, id) : stor.getAndSet(index, id);
		Palette<T> pal = d.palette();
		if (pal instanceof LinearPalette<T> lp) {
			return lp.valueFor(prevId);
		}
		return pal.valueFor(prevId);
	}
}
