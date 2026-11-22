package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BiomePrefixBlenderTest {
	@Test
	void matchesNaiveAverageForAllRadii() {
		Random rnd = new Random(42);
		BiomePrefixBlender blender = new BiomePrefixBlender();
		for (int radius = 0; radius <= 7; radius++) {
			int width = 16 + 2 * radius;
			int height = 16 + 2 * radius;
			int[] colors = new int[width * height];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = rnd.nextInt(0x1000000);
			}
			int[] out = new int[16 * 16];
			blender.blend(colors, width, height, radius, out);
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					assertEquals(BiomePrefixBlender.blendNaive(colors, width, x, z, radius), out[z * 16 + x],
						"radius " + radius + " at " + x + "," + z);
				}
			}
		}
	}

	@Test
	void uniformColorIsPreserved() {
		BiomePrefixBlender blender = new BiomePrefixBlender();
		int width = 20, height = 24, radius = 2;
		int[] colors = new int[width * height];
		java.util.Arrays.fill(colors, 0x7FB238);
		int[] out = new int[(width - 4) * (height - 4)];
		blender.blend(colors, width, height, radius, out);
		for (int c : out) {
			assertEquals(0x7FB238, c);
		}
	}

	@Test
	void nonSquareGridsAndScratchReuse() {
		Random rnd = new Random(7);
		BiomePrefixBlender blender = new BiomePrefixBlender();
		for (int[] dims : new int[][] {{11, 5, 1}, {40, 9, 3}, {7, 30, 2}}) {
			int w = dims[0], h = dims[1], r = dims[2];
			int[] colors = new int[w * h];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = rnd.nextInt(0x1000000);
			}
			int iw = w - 2 * r, ih = h - 2 * r;
			int[] out = new int[iw * ih];
			blender.blend(colors, w, h, r, out);
			for (int z = 0; z < ih; z++) {
				for (int x = 0; x < iw; x++) {
					assertEquals(BiomePrefixBlender.blendNaive(colors, w, x, z, r), out[z * iw + x]);
				}
			}
		}
	}

	@Test
	void rejectsBadInput() {
		BiomePrefixBlender blender = new BiomePrefixBlender();
		assertThrows(IllegalArgumentException.class, () -> blender.blend(new int[4], 2, 2, 1, new int[1]));
		assertThrows(IllegalArgumentException.class, () -> blender.blend(new int[4], 2, 2, -1, new int[4]));
	}

	@Test
	void blendOpaqueArgbSetsAlphaAndMatchesNaive() {
		Random rnd = new Random(99);
		BiomePrefixBlender blender = new BiomePrefixBlender();
		for (int radius = 0; radius <= 7; radius++) {
			int side = 16 + 2 * radius;
			int[] colors = new int[side * side];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = 0xFF000000 | rnd.nextInt(0x1000000);
			}
			int[] out = new int[256];
			blender.blendOpaqueArgb(colors, side, side, radius, out);
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int expected = 0xFF000000 | BiomePrefixBlender.blendNaive(colors, side, x, z, radius);
					assertEquals(expected, out[z * 16 + x], "radius " + radius + " at " + x + "," + z);
				}
			}
		}
	}
}
