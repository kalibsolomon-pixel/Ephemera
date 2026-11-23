package com.ephemera.mixin.common;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Devirtualizes the megamorphic {@code Palette.valueFor} and bimorphic {@code BitStorage.get}
 * interface call sites in {@link PalettedContainer#get(int)}, avoids redundant volatile
 * {@code this.data} re-reads in {@link PalettedContainer#getAndSet(int, Object)}, and pools the
 * temporary {@code int[]} scratch buffer inside {@code reencodeContents} (eliminating ~100 MB/s
 * of worker {@code int[4096]} allocations during chunk serialization/deserialization).
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerFastMixin<T> {
	@Unique
	private static final ThreadLocal<int[]> EPHEMERA$REENCODE_4096 = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<int[]> EPHEMERA$REENCODE_64 = new ThreadLocal<>();

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

	/**
	 * @author Ephemera
	 * @reason Reuses per-thread scratch int[] for private zero-escape reencodeContents (only read by SimpleBitStorage.<init>).
	 */
	@Overwrite
	private static <T> int[] reencodeContents(BitStorage storage, Palette<T> oldPalette, Palette<T> newPalette) {
		int size = storage.getSize();
		int[] contents;
		if (size == 4096) {
			contents = EPHEMERA$REENCODE_4096.get();
			if (contents == null) {
				contents = new int[4096];
				EPHEMERA$REENCODE_4096.set(contents);
			}
		} else if (size == 64) {
			contents = EPHEMERA$REENCODE_64.get();
			if (contents == null) {
				contents = new int[64];
				EPHEMERA$REENCODE_64.set(contents);
			}
		} else {
			contents = new int[size];
		}
		storage.unpack(contents);
		PaletteResize<T> noResize = PaletteResize.noResizeExpected();
		int prevOld = -1;
		int prevNew = -1;
		for (int i = 0; i < size; i++) {
			int oldId = contents[i];
			if (oldId != prevOld) {
				prevOld = oldId;
				prevNew = newPalette.idFor(oldPalette.valueFor(oldId), noResize);
			}
			contents[i] = prevNew;
		}
		return contents;
	}
}
