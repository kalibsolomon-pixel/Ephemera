package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.XoroshiroPositionalFastAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Zero-allocation primitive access to {@code XoroshiroPositionalRandomFactory} seeds and
 * single-sample float draws.
 */
@Mixin(XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class)
abstract class XoroshiroPositionalFastMixin implements XoroshiroPositionalFastAccess {
	@Shadow @Final private long seedLo;
	@Shadow @Final private long seedHi;

	@Override
	public float ephemera$nextFloatAt(int x, int y, int z) {
		long lo = Mth.getSeed(x, y, z) ^ this.seedLo;
		long hi = this.seedHi;
		if ((lo | hi) == 0L) {
			lo = -7046029254386353131L;
			hi = 7640891576956012809L;
		}
		long nextBits24 = (Long.rotateLeft(lo + hi, 17) + lo) >>> 40;
		return (float) nextBits24 * 5.9604645E-8f;
	}

	@Override
	public long ephemera$seedLo() {
		return this.seedLo;
	}

	@Override
	public long ephemera$seedHi() {
		return this.seedHi;
	}
}
