package com.ephemera.visuals;

import com.ephemera.core.visuals.ChunkFadeMath;
import com.mojang.blaze3d.vertex.QuadInstance;

/**
 * Applies the AO contrast curve to the four corner colors the smooth-lighting pass produces.
 * Runs on chunk builder threads, so the lookup table is published as an immutable pair.
 */
public final class AoContrast {
	private record Lut(float strength, int[] table) {}

	private static volatile Lut current = new Lut(1.0f, ChunkFadeMath.buildAoLut(1.0f));

	private AoContrast() {}

	public static void apply(QuadInstance quad) {
		if (!VisualsRuntime.enhancedAmbientOcclusion()) {
			return;
		}
		float strength = VisualsRuntime.cfg().visualAoStrength;
		if (strength == 1.0f) {
			return;
		}
		int[] table = lut(strength);
		for (int corner = 0; corner < 4; corner++) {
			int argb = quad.getColor(corner);
			int r = table[(argb >> 16) & 0xFF];
			int g = table[(argb >> 8) & 0xFF];
			int b = table[argb & 0xFF];
			quad.setColor(corner, (argb & 0xFF000000) | (r << 16) | (g << 8) | b);
		}
	}

	private static int[] lut(float strength) {
		Lut lut = current;
		if (lut.strength() != strength) {
			lut = new Lut(strength, ChunkFadeMath.buildAoLut(strength));
			current = lut;
		}
		return lut.table();
	}
}
