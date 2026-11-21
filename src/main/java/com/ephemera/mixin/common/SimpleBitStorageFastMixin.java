package com.ephemera.mixin.common;

import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates {@code org.apache.commons.lang3.Validate.inclusiveBetween} calls and repeated
 * {@code Integer.toUnsignedLong} conversions on every block/biome read and write in
 * {@link SimpleBitStorage}. Preserves exact exception semantics for out-of-range inputs.
 */
@Mixin(SimpleBitStorage.class)
abstract class SimpleBitStorageFastMixin {
	@Shadow @Final private long[] data;
	@Shadow @Final private int bits;
	@Shadow @Final private long mask;
	@Shadow @Final private int size;
	@Shadow @Final private int valuesPerLong;
	@Shadow @Final private int divideMul;
	@Shadow @Final private int divideAdd;
	@Shadow @Final private int divideShift;

	@Unique private long ephemera$divMulU;
	@Unique private long ephemera$divAddU;

	@Inject(method = "<init>(II[J)V", at = @At("TAIL"))
	private void ephemera$cacheUnsignedDivisors(int bits, int size, long[] data, CallbackInfo ci) {
		this.ephemera$divMulU = Integer.toUnsignedLong(this.divideMul);
		this.ephemera$divAddU = Integer.toUnsignedLong(this.divideAdd);
	}

	/**
	 * @author Ephemera
	 * @reason Single unsigned bounds check + cached unsigned multiply-shift without Validate.inclusiveBetween.
	 */
	@Overwrite
	public int get(int index) {
		if (Integer.compareUnsigned(index, this.size) >= 0) {
			throw new IllegalArgumentException("Index out of bounds: " + index + " (size " + this.size + ")");
		}
		int cell = (int) (((long) index * this.ephemera$divMulU + this.ephemera$divAddU) >> 32 >> this.divideShift);
		int bitOffset = (index - cell * this.valuesPerLong) * this.bits;
		return (int) (this.data[cell] >> bitOffset & this.mask);
	}

	/**
	 * @author Ephemera
	 * @reason Single unsigned bounds checks + cached unsigned multiply-shift without Validate.inclusiveBetween.
	 */
	@Overwrite
	public void set(int index, int value) {
		if (Integer.compareUnsigned(index, this.size) >= 0) {
			throw new IllegalArgumentException("Index out of bounds: " + index + " (size " + this.size + ")");
		}
		long m = this.mask;
		if (Long.compareUnsigned((long) value, m) > 0) {
			throw new IllegalArgumentException("Value out of bounds: " + value + " (mask " + m + ")");
		}
		int cell = (int) (((long) index * this.ephemera$divMulU + this.ephemera$divAddU) >> 32 >> this.divideShift);
		int bitOffset = (index - cell * this.valuesPerLong) * this.bits;
		long[] d = this.data;
		d[cell] = (d[cell] & ~(m << bitOffset)) | ((long) value << bitOffset);
	}

	/**
	 * @author Ephemera
	 * @reason Single unsigned bounds checks + cached unsigned multiply-shift without Validate.inclusiveBetween.
	 */
	@Overwrite
	public int getAndSet(int index, int value) {
		if (Integer.compareUnsigned(index, this.size) >= 0) {
			throw new IllegalArgumentException("Index out of bounds: " + index + " (size " + this.size + ")");
		}
		long m = this.mask;
		if (Long.compareUnsigned((long) value, m) > 0) {
			throw new IllegalArgumentException("Value out of bounds: " + value + " (mask " + m + ")");
		}
		int cell = (int) (((long) index * this.ephemera$divMulU + this.ephemera$divAddU) >> 32 >> this.divideShift);
		int bitOffset = (index - cell * this.valuesPerLong) * this.bits;
		long[] d = this.data;
		long cur = d[cell];
		int prev = (int) (cur >> bitOffset & m);
		d[cell] = (cur & ~(m << bitOffset)) | ((long) value << bitOffset);
		return prev;
	}
}
