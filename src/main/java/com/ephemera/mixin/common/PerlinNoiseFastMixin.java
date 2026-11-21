package com.ephemera.mixin.common;

import com.ephemera.core.math.FastNoiseMath;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pre-compacts non-null octaves and their exact input/amplitude/value factors at {@link PerlinNoise}
 * construction time, specializes the 3-arg {@code getValue}, and accelerates {@link PerlinNoise#wrap}
 * with a fast range bypass for normal world coordinates. 100% bit-exact IEEE-754 parity.
 */
@Mixin(PerlinNoise.class)
abstract class PerlinNoiseFastMixin {
	@Shadow @Final private ImprovedNoise[] noiseLevels;
	@Shadow @Final private DoubleList amplitudes;
	@Shadow @Final private double lowestFreqValueFactor;
	@Shadow @Final private double lowestFreqInputFactor;

	@Unique private ImprovedNoise[] ephemera$activeLevels;
	@Unique private double[] ephemera$inputFactors;
	@Unique private double[] ephemera$amps;
	@Unique private double[] ephemera$valueFactors;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$precompactOctaves(RandomSource random, Pair<Integer, DoubleList> octaves,
											boolean useNewInitialization, CallbackInfo ci) {
		ImprovedNoise[] levels = this.noiseLevels;
		int count = 0;
		for (ImprovedNoise lvl : levels) {
			if (lvl != null) {
				count++;
			}
		}
		ImprovedNoise[] active = new ImprovedNoise[count];
		double[] inFacs = new double[count];
		double[] amps = new double[count];
		double[] valFacs = new double[count];
		double inFactor = this.lowestFreqInputFactor;
		double valFactor = this.lowestFreqValueFactor;
		int out = 0;
		for (int i = 0; i < levels.length; i++) {
			ImprovedNoise lvl = levels[i];
			if (lvl != null) {
				active[out] = lvl;
				inFacs[out] = inFactor;
				amps[out] = this.amplitudes.getDouble(i);
				valFacs[out] = valFactor;
				out++;
			}
			inFactor *= 2.0;
			valFactor /= 2.0;
		}
		this.ephemera$activeLevels = active;
		this.ephemera$inputFactors = inFacs;
		this.ephemera$amps = amps;
		this.ephemera$valueFactors = valFacs;
	}

	/**
	 * @author Ephemera
	 * @reason Pre-compacted dense octave loop specialized for the common yScale==0 path.
	 */
	@Overwrite
	public double getValue(double x, double y, double z) {
		ImprovedNoise[] levels = this.ephemera$activeLevels;
		double[] inFacs = this.ephemera$inputFactors;
		double[] amps = this.ephemera$amps;
		double[] valFacs = this.ephemera$valueFactors;
		double acc = 0.0;
		for (int i = 0, n = levels.length; i < n; i++) {
			double f = inFacs[i];
			double sample = levels[i].noise(
				FastNoiseMath.wrap(x * f),
				FastNoiseMath.wrap(y * f),
				FastNoiseMath.wrap(z * f)
			);
			acc += amps[i] * sample * valFacs[i];
		}
		return acc;
	}

	/**
	 * @author Ephemera
	 * @reason Pre-compacted dense octave loop without null checks or DoubleList interface dispatch.
	 */
	@Overwrite
	public double getValue(double x, double y, double z, double yScale, double yMax) {
		if (yScale == 0.0) {
			return this.getValue(x, y, z);
		}
		ImprovedNoise[] levels = this.ephemera$activeLevels;
		double[] inFacs = this.ephemera$inputFactors;
		double[] amps = this.ephemera$amps;
		double[] valFacs = this.ephemera$valueFactors;
		double acc = 0.0;
		for (int i = 0, n = levels.length; i < n; i++) {
			double f = inFacs[i];
			double sample = levels[i].noise(
				FastNoiseMath.wrap(x * f),
				FastNoiseMath.wrap(y * f),
				FastNoiseMath.wrap(z * f),
				yScale * f,
				yMax * f
			);
			acc += amps[i] * sample * valFacs[i];
		}
		return acc;
	}

	/**
	 * @author Ephemera
	 * @reason Bit-exact range bypass avoids division and floor for coordinates in [-16777216, 16777216).
	 */
	@Overwrite
	public static double wrap(double value) {
		return FastNoiseMath.wrap(value);
	}
}
