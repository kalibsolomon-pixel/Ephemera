package com.ephemera.mixin.common.worldgen;

import com.ephemera.core.math.FastNoiseMath;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Hoists {@code perms} and replaces virtual {@code permute} / {@code permuteToGrad} calls and
 * {@code GradientNoise.Gradient} record chases in 26.3 {@link PerlinNoise#sampleAndLerp} and
 * {@link PerlinNoise#addToVolume} with flat L1-resident primitive tables. Bit-exact float parity.
 */
@Mixin(PerlinNoise.class)
abstract class PerlinNoiseFastMixin extends GradientNoise {
	protected PerlinNoiseFastMixin(RandomSource random) {
		super(random);
	}

	/**
	 * @author Ephemera
	 * @reason Hoisted permutation array and flat float gradient tables with bit-exact Perlin math.
	 */
	@Overwrite
	protected float sampleAndLerp(int xi, int yi, int zi, float xf, float yf, float zf, float yfFade) {
		byte[] p = this.perms;
		int px0 = p[xi & 255] & 255;
		int px1 = p[(xi + 1) & 255] & 255;
		int pxy00 = p[(px0 + yi) & 255] & 255;
		int pxy01 = p[(px0 + yi + 1) & 255] & 255;
		int pxy10 = p[(px1 + yi) & 255] & 255;
		int pxy11 = p[(px1 + yi + 1) & 255] & 255;

		float xf1 = xf - 1.0f;
		float yf1 = yf - 1.0f;
		float zf1 = zf - 1.0f;
		int zi1 = zi + 1;

		float d000 = FastNoiseMath.gradDotF(p[(pxy00 + zi) & 255], xf, yf, zf);
		float d100 = FastNoiseMath.gradDotF(p[(pxy10 + zi) & 255], xf1, yf, zf);
		float d010 = FastNoiseMath.gradDotF(p[(pxy01 + zi) & 255], xf, yf1, zf);
		float d110 = FastNoiseMath.gradDotF(p[(pxy11 + zi) & 255], xf1, yf1, zf);
		float d001 = FastNoiseMath.gradDotF(p[(pxy00 + zi1) & 255], xf, yf, zf1);
		float d101 = FastNoiseMath.gradDotF(p[(pxy10 + zi1) & 255], xf1, yf, zf1);
		float d011 = FastNoiseMath.gradDotF(p[(pxy01 + zi1) & 255], xf, yf1, zf1);
		float d111 = FastNoiseMath.gradDotF(p[(pxy11 + zi1) & 255], xf1, yf1, zf1);

		float u = Mth.smoothstep(xf);
		float v = Mth.smoothstep(yfFade);
		float w = Mth.smoothstep(zf);
		return Mth.lerp3(u, v, w, d000, d100, d010, d110, d001, d101, d011, d111);
	}

	/**
	 * @author Ephemera
	 * @reason Hoisted permutation array and flat gradient tables eliminate 12 virtual calls and 8 record dereferences per Y-cell transition.
	 */
	@Overwrite
	public void addToVolume(DensityBuffer output, DensityVolume volume, double xzScale, double yScale, float amplitude) {
		byte[] p = this.perms;
		double ox = this.offsetX;
		double oy = this.offsetY;
		double oz = this.offsetZ;
		int sizeZ = volume.sizeZ();
		int sizeX = volume.sizeX();
		int sizeY = volume.sizeY();

		float d000 = 0.0f, d100 = 0.0f, d010 = 0.0f, d110 = 0.0f;
		float d001 = 0.0f, d101 = 0.0f, d011 = 0.0f, d111 = 0.0f;
		float gy000 = 0.0f, gy100 = 0.0f, gy010 = 0.0f, gy110 = 0.0f;
		float gy001 = 0.0f, gy101 = 0.0f, gy011 = 0.0f, gy111 = 0.0f;

		int idx = 0;
		for (int iz = 0; iz < sizeZ; iz++) {
			double z = wrap((double) volume.blockZ(iz) * xzScale) + oz;
			int zi = Mth.floor(z);
			int zi1 = zi + 1;
			float zf = (float) (z - (double) zi);
			float zf1 = zf - 1.0f;
			float w = Mth.smoothstep(zf);

			for (int ix = 0; ix < sizeX; ix++) {
				double x = wrap((double) volume.blockX(ix) * xzScale) + ox;
				int xi = Mth.floor(x);
				float xf = (float) (x - (double) xi);
				float xf1 = xf - 1.0f;
				int px0 = p[xi & 255] & 255;
				int px1 = p[(xi + 1) & 255] & 255;
				float u = Mth.smoothstep(xf);

				int prevYi = Integer.MIN_VALUE;
				for (int iy = 0; iy < sizeY; iy++) {
					double y = wrap((double) volume.blockY(iy) * yScale) + oy;
					int yi = Mth.floor(y);
					float yf = (float) (y - (double) yi);
					float v = Mth.smoothstep(yf);
					if (prevYi != yi) {
						int pxy00 = p[(px0 + yi) & 255] & 255;
						int pxy01 = p[(px0 + yi + 1) & 255] & 255;
						int pxy10 = p[(px1 + yi) & 255] & 255;
						int pxy11 = p[(px1 + yi + 1) & 255] & 255;

						int g000 = p[(pxy00 + zi) & 255];
						d000 = FastNoiseMath.gradDotXzF(g000, xf, zf);
						gy000 = FastNoiseMath.gradYF(g000);

						int g100 = p[(pxy10 + zi) & 255];
						d100 = FastNoiseMath.gradDotXzF(g100, xf1, zf);
						gy100 = FastNoiseMath.gradYF(g100);

						int g010 = p[(pxy01 + zi) & 255];
						d010 = FastNoiseMath.gradDotXzF(g010, xf, zf);
						gy010 = FastNoiseMath.gradYF(g010);

						int g110 = p[(pxy11 + zi) & 255];
						d110 = FastNoiseMath.gradDotXzF(g110, xf1, zf);
						gy110 = FastNoiseMath.gradYF(g110);

						int g001 = p[(pxy00 + zi1) & 255];
						d001 = FastNoiseMath.gradDotXzF(g001, xf, zf1);
						gy001 = FastNoiseMath.gradYF(g001);

						int g101 = p[(pxy10 + zi1) & 255];
						d101 = FastNoiseMath.gradDotXzF(g101, xf1, zf1);
						gy101 = FastNoiseMath.gradYF(g101);

						int g011 = p[(pxy01 + zi1) & 255];
						d011 = FastNoiseMath.gradDotXzF(g011, xf, zf1);
						gy011 = FastNoiseMath.gradYF(g011);

						int g111 = p[(pxy11 + zi1) & 255];
						d111 = FastNoiseMath.gradDotXzF(g111, xf1, zf1);
						gy111 = FastNoiseMath.gradYF(g111);

						prevYi = yi;
					}
					float yf1 = yf - 1.0f;
					output.addTo(idx++, amplitude * Mth.lerp3(
						u, v, w,
						d000 + gy000 * yf,
						d100 + gy100 * yf,
						d010 + gy010 * yf1,
						d110 + gy110 * yf1,
						d001 + gy001 * yf,
						d101 + gy101 * yf,
						d011 + gy011 * yf1,
						d111 + gy111 * yf1
					));
				}
			}
		}
	}
}
