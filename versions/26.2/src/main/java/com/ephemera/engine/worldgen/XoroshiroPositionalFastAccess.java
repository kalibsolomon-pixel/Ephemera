package com.ephemera.engine.worldgen;

/**
 * Zero-allocation primitive Xoroshiro128++ access on {@code XoroshiroPositionalRandomFactory} for
 * {@code VerticalGradientCondition} and {@code OreVeinifier}.
 */
public interface XoroshiroPositionalFastAccess {
	float ephemera$nextFloatAt(int x, int y, int z);
	long ephemera$seedLo();
	long ephemera$seedHi();
}
