package com.ephemera.core.math;

/**
 * Bit-exact fast implementations of worldgen Perlin/Improved noise and 3D Voronoi
 * biome zooming math. Zero Minecraft/LWJGL dependencies so every path can be verified
 * exhaustively in standalone JUnit tests.
 */
public final class FastNoiseMath {
	private static final double WRAP_PERIOD = 3.3554432E7; // 2^25
	private static final double WRAP_HALF = 1.6777216E7;   // 2^24
	private static final double SHIFT_UP_EPSILON = 1.0000000116860974E-7; // (double) 1.0E-7F
	private static final double INV_1024 = 1.0 / 1024.0;

	private static final long LCG_MULT = 6364136223846793005L;
	private static final long LCG_INC = 1442695040888963407L;

	// L1D-resident flat gradient component tables matching SimplexNoise.GRADIENT[0..15].
	// Zero branch mispredictions and C2 proves (hash & 15) is always in [0, 15] (zero bounds checks).
	private static final double[] GRAD_X = {
		 1.0, -1.0,  1.0, -1.0,
		 1.0, -1.0,  1.0, -1.0,
		 0.0,  0.0,  0.0,  0.0,
		 1.0,  0.0, -1.0,  0.0
	};
	private static final double[] GRAD_Y = {
		 1.0,  1.0, -1.0, -1.0,
		 0.0,  0.0,  0.0,  0.0,
		 1.0, -1.0,  1.0, -1.0,
		 1.0, -1.0,  1.0, -1.0
	};
	private static final double[] GRAD_Z = {
		 0.0,  0.0,  0.0,  0.0,
		 1.0,  1.0, -1.0, -1.0,
		 1.0,  1.0, -1.0, -1.0,
		 0.0,  1.0,  0.0, -1.0
	};
	private static final float[] GRAD_XF = {
		 1.0f, -1.0f,  1.0f, -1.0f,
		 1.0f, -1.0f,  1.0f, -1.0f,
		 0.0f,  0.0f,  0.0f,  0.0f,
		 1.0f,  0.0f, -1.0f,  0.0f
	};
	private static final float[] GRAD_YF = {
		 1.0f,  1.0f, -1.0f, -1.0f,
		 0.0f,  0.0f,  0.0f,  0.0f,
		 1.0f, -1.0f,  1.0f, -1.0f,
		 1.0f, -1.0f,  1.0f, -1.0f
	};
	private static final float[] GRAD_ZF = {
		 0.0f,  0.0f,  0.0f,  0.0f,
		 1.0f,  1.0f, -1.0f, -1.0f,
		 1.0f,  1.0f, -1.0f, -1.0f,
		 0.0f,  1.0f,  0.0f, -1.0f
	};

	private FastNoiseMath() {}

	/**
	 * Branchless gradient dot product matching {@code SimplexNoise.GRADIENT[hash & 15]}
	 * bit-for-bit without 2D array indirection or unpredictable jump-table branches.
	 */
	public static double gradDot(int hash, double x, double y, double z) {
		int g = hash & 15;
		return (GRAD_X[g] * x + GRAD_Y[g] * y) + GRAD_Z[g] * z;
	}

	/** Branchless single-precision gradient dot product matching 26.3 {@code GradientNoise.gradDot}. */
	public static float gradDotF(int hash, float x, float y, float z) {
		int g = hash & 15;
		return (GRAD_XF[g] * x + GRAD_YF[g] * y) + GRAD_ZF[g] * z;
	}

	/** Branchless single-precision XZ gradient dot product matching 26.3 {@code GradientNoise.Gradient.dotXz}. */
	public static float gradDotXzF(int hash, float x, float z) {
		int g = hash & 15;
		return GRAD_XF[g] * x + GRAD_ZF[g] * z;
	}

	/** Single-precision Y gradient component matching 26.3 {@code (float) GradientNoise.Gradient.y()}. */
	public static float gradYF(int hash) {
		return GRAD_YF[hash & 15];
	}

	/** Exact quintic Perlin fade curve: {@code t^3 * (t * (6t - 15) + 10)}. */
	public static double smoothstep(double t) {
		return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
	}

	/** Exact trilinear interpolation matching {@code Mth.lerp3}. */
	public static double lerp3(double dx, double dy, double dz,
							   double v000, double v100, double v010, double v110,
							   double v001, double v101, double v011, double v111) {
		double x00 = v000 + dx * (v100 - v000);
		double x10 = v010 + dx * (v110 - v010);
		double y0 = x00 + dy * (x10 - x00);
		double x01 = v001 + dx * (v101 - v001);
		double x11 = v011 + dx * (v111 - v011);
		double y1 = x01 + dy * (x11 - x01);
		return y0 + dz * (y1 - y0);
	}

	/**
	 * Exact {@code PerlinNoise.wrap(double)} with a fast no-op range check for coordinates
	 * within {@code [-16777216, +16777216)} (99.999% of normal Minecraft gameplay).
	 */
	public static double wrap(double value) {
		if (value >= -WRAP_HALF && value < WRAP_HALF) {
			return value;
		}
		double scaled = value / WRAP_PERIOD + 0.5;
		long floored = (long) scaled;
		if (scaled < (double) floored) {
			floored--;
		}
		return value - (double) floored * WRAP_PERIOD;
	}

	/** Exact floor matching {@code Mth.floor(double)}. */
	public static int floorInt(double v) {
		int i = (int) v;
		return v < (double) i ? i - 1 : i;
	}

	/**
	 * Fast specialization of {@code ImprovedNoise.noise(x, y, z)} (where {@code yScale == 0})
	 * given pre-offset coordinates {@code x = inX + xo}, {@code y = inY + yo}, {@code z = inZ + zo}.
	 */
	public static double sampleImprovedNoYScale(byte[] p, double x, double y, double z) {
		int xi = floorInt(x);
		int yi = floorInt(y);
		int zi = floorInt(z);
		double xf = x - (double) xi;
		double yf = y - (double) yi;
		double zf = z - (double) zi;
		return sampleAndLerp(p, xi, yi, zi, xf, yf, zf, yf);
	}

	/**
	 * Full {@code ImprovedNoise.noise(x, y, z, yScale, yMax)} given pre-offset coordinates
	 * {@code x = inX + xo}, {@code y = inY + yo}, {@code z = inZ + zo}.
	 */
	public static double sampleImproved(byte[] p, double x, double y, double z, double yScale, double yMax) {
		int xi = floorInt(x);
		int yi = floorInt(y);
		int zi = floorInt(z);
		double xf = x - (double) xi;
		double yf = y - (double) yi;
		double zf = z - (double) zi;
		double yShift;
		if (yScale != 0.0) {
			double clamped = (yMax >= 0.0 && yMax < yf) ? yMax : yf;
			yShift = (double) floorInt(clamped / yScale + SHIFT_UP_EPSILON) * yScale;
		} else {
			yShift = 0.0;
		}
		return sampleAndLerp(p, xi, yi, zi, xf, yf - yShift, zf, yf);
	}

	/** Core 8-corner Perlin gradient evaluation and trilinear interpolation. */
	public static double sampleAndLerp(byte[] p, int xi, int yi, int zi,
									   double xf, double yf, double zf, double yfFade) {
		int px0 = p[xi & 255] & 255;
		int px1 = p[(xi + 1) & 255] & 255;
		int pxy00 = p[(px0 + yi) & 255] & 255;
		int pxy01 = p[(px0 + yi + 1) & 255] & 255;
		int pxy10 = p[(px1 + yi) & 255] & 255;
		int pxy11 = p[(px1 + yi + 1) & 255] & 255;

		double xf1 = xf - 1.0;
		double yf1 = yf - 1.0;
		double zf1 = zf - 1.0;

		int z0 = zi;
		int z1 = zi + 1;
		double d000 = gradDot(p[(pxy00 + z0) & 255], xf, yf, zf);
		double d100 = gradDot(p[(pxy10 + z0) & 255], xf1, yf, zf);
		double d010 = gradDot(p[(pxy01 + z0) & 255], xf, yf1, zf);
		double d110 = gradDot(p[(pxy11 + z0) & 255], xf1, yf1, zf);
		double d001 = gradDot(p[(pxy00 + z1) & 255], xf, yf, zf1);
		double d101 = gradDot(p[(pxy10 + z1) & 255], xf1, yf, zf1);
		double d011 = gradDot(p[(pxy01 + z1) & 255], xf, yf1, zf1);
		double d111 = gradDot(p[(pxy11 + z1) & 255], xf1, yf1, zf1);

		double u = smoothstep(xf);
		double v = smoothstep(yfFade);
		double w = smoothstep(zf);
		return lerp3(u, v, w, d000, d100, d010, d110, d001, d101, d011, d111);
	}

	/** Exact {@code LinearCongruentialGenerator.next(long, long)}. */
	public static long lcgNext(long seed, long salt) {
		return seed * (seed * LCG_MULT + LCG_INC) + salt;
	}

	/** Exact {@code BiomeManager.getFiddle(long)} without signed {@code Math.floorMod} division. */
	public static double voronoiFiddle(long seed) {
		return ((double) ((int) ((seed >> 24) & 1023L)) * INV_1024 - 0.5) * 0.9;
	}

	/**
	 * Exact 8-corner 3D Voronoi nearest-cell selector for {@code BiomeManager.getBiome(BlockPos)}.
	 * Reuses shared X and (X,Y) LCG prefixes across corners and returns the winning corner
	 * index {@code 0..7} (bit 2 = +X, bit 1 = +Y, bit 0 = +Z).
	 */
	public static int voronoiBiomeCorner(long zoomSeed, int qx, int qy, int qz,
										 double fx, double fy, double fz) {
		int qx1 = qx + 1;
		int qy1 = qy + 1;
		int qz1 = qz + 1;
		double fx1 = fx - 1.0;
		double fy1 = fy - 1.0;
		double fz1 = fz - 1.0;

		// Corner group X = qx (indices 0..3)
		long sx0 = lcgNext(zoomSeed, qx);

		long sxy00 = lcgNext(sx0, qy);
		double bestDist = cornerDist(zoomSeed, sxy00, qx, qy, qz, fx, fy, fz);
		int bestIdx = 0;

		double d = cornerDist(zoomSeed, sxy00, qx, qy, qz1, fx, fy, fz1);
		if (d < bestDist) {
			bestDist = d;
			bestIdx = 1;
		}

		long sxy01 = lcgNext(sx0, qy1);
		d = cornerDist(zoomSeed, sxy01, qx, qy1, qz, fx, fy1, fz);
		if (d < bestDist) {
			bestDist = d;
			bestIdx = 2;
		}

		d = cornerDist(zoomSeed, sxy01, qx, qy1, qz1, fx, fy1, fz1);
		if (d < bestDist) {
			bestDist = d;
			bestIdx = 3;
		}

		// Corner group X = qx + 1 (indices 4..7)
		long sx1 = lcgNext(zoomSeed, qx1);

		long sxy10 = lcgNext(sx1, qy);
		d = cornerDist(zoomSeed, sxy10, qx1, qy, qz, fx1, fy, fz);
		if (d < bestDist) {
			bestDist = d;
			bestIdx = 4;
		}

		d = cornerDist(zoomSeed, sxy10, qx1, qy, qz1, fx1, fy, fz1);
		if (d < bestDist) {
			bestDist = d;
			bestIdx = 5;
		}

		long sxy11 = lcgNext(sx1, qy1);
		d = cornerDist(zoomSeed, sxy11, qx1, qy1, qz, fx1, fy1, fz);
		if (d < bestDist) {
			bestDist = d;
			bestIdx = 6;
		}

		d = cornerDist(zoomSeed, sxy11, qx1, qy1, qz1, fx1, fy1, fz1);
		if (d < bestDist) {
			bestIdx = 7;
		}

		return bestIdx;
	}

	private static double cornerDist(long zoomSeed, long sxy, int gx, int gy, int gz,
									 double dx, double dy, double dz) {
		long s = lcgNext(sxy, gz);
		s = lcgNext(s, gx);
		s = lcgNext(s, gy);
		s = lcgNext(s, gz);
		double offX = voronoiFiddle(s);
		s = lcgNext(s, zoomSeed);
		double offY = voronoiFiddle(s);
		s = lcgNext(s, zoomSeed);
		double offZ = voronoiFiddle(s);
		double tx = dx + offX;
		double ty = dy + offY;
		double tz = dz + offZ;
		// Preserve vanilla addition order: tz^2 + ty^2 + tx^2
		return (tz * tz + ty * ty) + tx * tx;
	}
}
