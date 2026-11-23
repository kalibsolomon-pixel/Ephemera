package com.ephemera.core.math;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FastNoiseMathTest {
	private static final int[][] VANILLA_GRADIENT = {
		{ 1,  1,  0}, {-1,  1,  0}, { 1, -1,  0}, {-1, -1,  0},
		{ 1,  0,  1}, {-1,  0,  1}, { 1,  0, -1}, {-1,  0, -1},
		{ 0,  1,  1}, { 0, -1,  1}, { 0,  1, -1}, { 0, -1, -1},
		{ 1,  1,  0}, { 0, -1,  1}, {-1,  1,  0}, { 0, -1, -1}
	};

	private static double vanillaDot(int[] g, double x, double y, double z) {
		return (double) g[0] * x + (double) g[1] * y + (double) g[2] * z;
	}

	private static double vanillaWrap(double v) {
		double s = v / 3.3554432E7 + 0.5;
		long l = (long) s;
		if (s < (double) l) l--;
		return v - (double) l * 3.3554432E7;
	}

	private static double vanillaImproved(byte[] p, double xo, double yo, double zo,
										  double x, double y, double z, double yScale, double yMax) {
		double ax = x + xo, ay = y + yo, az = z + zo;
		int xi = FastNoiseMath.floorInt(ax);
		int yi = FastNoiseMath.floorInt(ay);
		int zi = FastNoiseMath.floorInt(az);
		double xf = ax - xi, yf = ay - yi, zf = az - zi;
		double yShift = 0.0;
		if (yScale != 0.0) {
			double d = (yMax >= 0.0 && yMax < yf) ? yMax : yf;
			yShift = (double) FastNoiseMath.floorInt(d / yScale + (double) 1.0E-7F) * yScale;
		}
		double yAdj = yf - yShift;
		int a = p[xi & 255] & 255;
		int b = p[(xi + 1) & 255] & 255;
		int aa = p[(a + yi) & 255] & 255;
		int ab = p[(a + yi + 1) & 255] & 255;
		int ba = p[(b + yi) & 255] & 255;
		int bb = p[(b + yi + 1) & 255] & 255;
		double d0 = vanillaDot(VANILLA_GRADIENT[p[(aa + zi) & 255] & 15], xf, yAdj, zf);
		double d1 = vanillaDot(VANILLA_GRADIENT[p[(ba + zi) & 255] & 15], xf - 1.0, yAdj, zf);
		double d2 = vanillaDot(VANILLA_GRADIENT[p[(ab + zi) & 255] & 15], xf, yAdj - 1.0, zf);
		double d3 = vanillaDot(VANILLA_GRADIENT[p[(bb + zi) & 255] & 15], xf - 1.0, yAdj - 1.0, zf);
		double d4 = vanillaDot(VANILLA_GRADIENT[p[(aa + zi + 1) & 255] & 15], xf, yAdj, zf - 1.0);
		double d5 = vanillaDot(VANILLA_GRADIENT[p[(ba + zi + 1) & 255] & 15], xf - 1.0, yAdj, zf - 1.0);
		double d6 = vanillaDot(VANILLA_GRADIENT[p[(ab + zi + 1) & 255] & 15], xf, yAdj - 1.0, zf - 1.0);
		double d7 = vanillaDot(VANILLA_GRADIENT[p[(bb + zi + 1) & 255] & 15], xf - 1.0, yAdj - 1.0, zf - 1.0);
		double u = FastNoiseMath.smoothstep(xf);
		double v = FastNoiseMath.smoothstep(yf);
		double w = FastNoiseMath.smoothstep(zf);
		return FastNoiseMath.lerp3(u, v, w, d0, d1, d2, d3, d4, d5, d6, d7);
	}

	private static int vanillaVoronoi(long seed, int bx, int by, int bz) {
		int x = bx - 2, y = by - 2, z = bz - 2;
		int qx = x >> 2, qy = y >> 2, qz = z >> 2;
		double fx = (double) (x & 3) / 4.0;
		double fy = (double) (y & 3) / 4.0;
		double fz = (double) (z & 3) / 4.0;
		int best = 0;
		double bestDist = Double.POSITIVE_INFINITY;
		for (int i = 0; i < 8; i++) {
			boolean x0 = (i & 4) == 0;
			boolean y0 = (i & 2) == 0;
			boolean z0 = (i & 1) == 0;
			int gx = x0 ? qx : qx + 1;
			int gy = y0 ? qy : qy + 1;
			int gz = z0 ? qz : qz + 1;
			double dx = x0 ? fx : fx - 1.0;
			double dy = y0 ? fy : fy - 1.0;
			double dz = z0 ? fz : fz - 1.0;
			long s = seed;
			s = FastNoiseMath.lcgNext(s, gx);
			s = FastNoiseMath.lcgNext(s, gy);
			s = FastNoiseMath.lcgNext(s, gz);
			s = FastNoiseMath.lcgNext(s, gx);
			s = FastNoiseMath.lcgNext(s, gy);
			s = FastNoiseMath.lcgNext(s, gz);
			double f0 = ((double) Math.floorMod(s >> 24, 1024) / 1024.0 - 0.5) * 0.9;
			s = FastNoiseMath.lcgNext(s, seed);
			double f1 = ((double) Math.floorMod(s >> 24, 1024) / 1024.0 - 0.5) * 0.9;
			s = FastNoiseMath.lcgNext(s, seed);
			double f2 = ((double) Math.floorMod(s >> 24, 1024) / 1024.0 - 0.5) * 0.9;
			double dist = (dz + f2) * (dz + f2) + (dy + f1) * (dy + f1) + (dx + f0) * (dx + f0);
			if (bestDist > dist) {
				best = i;
				bestDist = dist;
			}
		}
		return best;
	}

	@Test
	void gradDotMatchesVanillaBitForBit() {
		Random rng = new Random(0x5EED1234L);
		double[] edgeVals = {0.0, 1.0, -1.0, 0.5, -0.5, 1e-12, -1e-12, 0.9999999999};
		for (int h = -32; h < 288; h++) {
			for (double x : edgeVals) {
				for (double y : edgeVals) {
					double z = edgeVals[(h + 32) % edgeVals.length];
					double expected = vanillaDot(VANILLA_GRADIENT[h & 15], x, y, z);
					double actual = FastNoiseMath.gradDot(h, x, y, z);
					assertEquals(expected, actual, 0.0, "hash=" + h);
				}
			}
		}
		for (int i = 0; i < 10_000; i++) {
			int h = rng.nextInt();
			double x = rng.nextDouble() * 2.0 - 1.0;
			double y = rng.nextDouble() * 2.0 - 1.0;
			double z = rng.nextDouble() * 2.0 - 1.0;
			assertEquals(
				Double.doubleToLongBits(vanillaDot(VANILLA_GRADIENT[h & 15], x, y, z)),
				Double.doubleToLongBits(FastNoiseMath.gradDot(h, x, y, z))
			);
		}
	}

	@Test
	void gradDotFloatMatchesVanillaBitForBit() {
		Random rng = new Random(0x5EED5678L);
		for (int h = -32; h < 288; h++) {
			int[] g = VANILLA_GRADIENT[h & 15];
			assertEquals(Float.floatToRawIntBits((float) g[1]), Float.floatToRawIntBits(FastNoiseMath.gradYF(h)));
			for (int i = 0; i < 16; i++) {
				float x = rng.nextFloat() * 2.0f - 1.0f;
				float y = rng.nextFloat() * 2.0f - 1.0f;
				float z = rng.nextFloat() * 2.0f - 1.0f;
				float expected3 = (float) g[0] * x + (float) g[1] * y + (float) g[2] * z;
				float expectedXz = (float) g[0] * x + (float) g[2] * z;
				assertEquals(Float.floatToRawIntBits(expected3), Float.floatToRawIntBits(FastNoiseMath.gradDotF(h, x, y, z)));
				assertEquals(Float.floatToRawIntBits(expectedXz), Float.floatToRawIntBits(FastNoiseMath.gradDotXzF(h, x, z)));
			}
		}
	}

	@Test
	void wrapMatchesVanillaBitForBit() {
		double[] special = {
			0.0, -0.0, 1.0, -1.0, 16777215.99999, 16777216.0, -16777216.0, -16777216.0001,
			33554432.0, -33554432.0, 1.0e12, -1.0e12, 123456789.123456
		};
		for (double v : special) {
			assertEquals(Double.doubleToLongBits(vanillaWrap(v)), Double.doubleToLongBits(FastNoiseMath.wrap(v)), "v=" + v);
		}
		Random rng = new Random(0xBEEFL);
		for (int i = 0; i < 20_000; i++) {
			double v = (rng.nextDouble() - 0.5) * 1.0e9;
			assertEquals(Double.doubleToLongBits(vanillaWrap(v)), Double.doubleToLongBits(FastNoiseMath.wrap(v)));
		}
	}

	@Test
	void improvedNoiseMatchesVanillaBitForBit() {
		Random rng = new Random(42L);
		byte[] p = new byte[256];
		for (int i = 0; i < 256; i++) p[i] = (byte) i;
		for (int i = 0; i < 256; i++) {
			int j = rng.nextInt(256 - i);
			byte tmp = p[i];
			p[i] = p[i + j];
			p[i + j] = tmp;
		}
		double xo = rng.nextDouble() * 256.0;
		double yo = rng.nextDouble() * 256.0;
		double zo = rng.nextDouble() * 256.0;

		for (int i = 0; i < 20_000; i++) {
			double x = (rng.nextDouble() - 0.5) * 4000.0;
			double y = (rng.nextDouble() - 0.5) * 400.0;
			double z = (rng.nextDouble() - 0.5) * 4000.0;
			double ref0 = vanillaImproved(p, xo, yo, zo, x, y, z, 0.0, 0.0);
			double fast0 = FastNoiseMath.sampleImprovedNoYScale(p, x + xo, y + yo, z + zo);
			assertEquals(Double.doubleToLongBits(ref0), Double.doubleToLongBits(fast0));

			double yScale = rng.nextBoolean() ? 0.0 : rng.nextDouble() * 4.0 + 0.01;
			double yMax = rng.nextBoolean() ? 0.0 : rng.nextDouble() * 2.0 - 0.5;
			double ref1 = vanillaImproved(p, xo, yo, zo, x, y, z, yScale, yMax);
			double fast1 = FastNoiseMath.sampleImproved(p, x + xo, y + yo, z + zo, yScale, yMax);
			assertEquals(Double.doubleToLongBits(ref1), Double.doubleToLongBits(fast1));
		}
	}

	@Test
	void voronoiBiomeMatchesVanillaBitForBit() {
		Random rng = new Random(0xCAFEBABEL);
		long[] seeds = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEF0L, rng.nextLong()};
		for (long seed : seeds) {
			for (int i = 0; i < 5_000; i++) {
				int bx = rng.nextInt(200_000) - 100_000;
				int by = rng.nextInt(512) - 64;
				int bz = rng.nextInt(200_000) - 100_000;
				int x = bx - 2, y = by - 2, z = bz - 2;
				int expected = vanillaVoronoi(seed, bx, by, bz);
				int actual = FastNoiseMath.voronoiBiomeCorner(
					seed, x >> 2, y >> 2, z >> 2,
					(x & 3) * 0.25, (y & 3) * 0.25, (z & 3) * 0.25
				);
				assertEquals(expected, actual, "seed=" + seed + " pos=" + bx + "," + by + "," + bz);
			}
		}
	}
}
