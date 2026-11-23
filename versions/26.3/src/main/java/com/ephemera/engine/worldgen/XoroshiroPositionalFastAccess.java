package com.ephemera.engine.worldgen;

/**
 * Zero-allocation single-sample float draw on {@code XoroshiroPositionalRandomFactory} for
 * {@code VerticalGradientCondition} (bit-exact parity with {@code at(x, y, z).nextFloat()}).
 */
public interface XoroshiroPositionalFastAccess {
	float ephemera$nextFloatAt(int x, int y, int z);
}
