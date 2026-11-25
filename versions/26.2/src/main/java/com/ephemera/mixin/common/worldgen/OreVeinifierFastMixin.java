package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.XoroshiroPositionalFastAccess;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Eliminates ~215 MB of {@code XoroshiroRandomSource} + {@code Xoroshiro128PlusPlus} +
 * {@code MarsagliaPolarGaussian} allocations inside {@link OreVeinifier#create} by evaluating the
 * 1–3 Xoroshiro128++ float draws directly in primitive stack registers.
 */
@Mixin(OreVeinifier.class)
abstract class OreVeinifierFastMixin {
	/**
	 * @author Ephemera
	 * @reason Primitive Xoroshiro128++ stack evaluation produces bit-exact ore veins with zero heap allocation.
	 */
	@Overwrite
	static NoiseChunk.BlockStateFiller create(
		DensityFunction veinToggle,
		DensityFunction veinRidged,
		DensityFunction veinGap,
		PositionalRandomFactory randomFactory
	) {
		BlockState defaultState = SharedConstants.DEBUG_ORE_VEINS ? Blocks.AIR.defaultBlockState() : null;
		XoroshiroPositionalFastAccess fastXor = (randomFactory instanceof XoroshiroPositionalFastAccess x) ? x : null;
		return ctx -> {
			double toggle = veinToggle.compute(ctx);
			int y = ctx.blockY();
			OreVeinifier.VeinType veinType = toggle > 0.0 ? OreVeinifier.VeinType.COPPER : OreVeinifier.VeinType.IRON;
			double absToggle = Math.abs(toggle);
			int distMax = veinType.maxY - y;
			int distMin = y - veinType.minY;
			if (distMin < 0 || distMax < 0) {
				return defaultState;
			}
			int edgeDist = Math.min(distMax, distMin);
			double roundoff = Mth.clampedMap((double) edgeDist, 0.0, 20.0, -0.2, 0.0);
			if (absToggle + roundoff < 0.4000000059604645) {
				return defaultState;
			}
			if (fastXor != null) {
				long lo = Mth.getSeed(ctx.blockX(), y, ctx.blockZ()) ^ fastXor.ephemera$seedLo();
				long hi = fastXor.ephemera$seedHi();
				if ((lo | hi) == 0L) {
					lo = -7046029254386353131L;
					hi = 7640891576956012809L;
				}
				// Roll 1: nextFloat() > 0.7f
				long r1 = Long.rotateLeft(lo + hi, 17) + lo;
				long t1 = hi ^ lo;
				lo = Long.rotateLeft(lo, 49) ^ t1 ^ (t1 << 21);
				hi = Long.rotateLeft(t1, 28);
				if (((float) (r1 >>> 40) * 5.9604645E-8f) > 0.7f) {
					return defaultState;
				}
				if (veinRidged.compute(ctx) >= 0.0) {
					return defaultState;
				}
				double richness = Mth.clampedMap(absToggle, 0.4000000059604645, 0.6000000238418579,
					0.10000000149011612, 0.30000001192092896);
				// Roll 2: nextFloat() < richness
				long r2 = Long.rotateLeft(lo + hi, 17) + lo;
				long t2 = hi ^ lo;
				lo = Long.rotateLeft(lo, 49) ^ t2 ^ (t2 << 21);
				hi = Long.rotateLeft(t2, 28);
				if ((double) ((float) (r2 >>> 40) * 5.9604645E-8f) < richness
					&& veinGap.compute(ctx) > -0.30000001192092896) {
					// Roll 3: nextFloat() < 0.02f
					long r3 = Long.rotateLeft(lo + hi, 17) + lo;
					return ((float) (r3 >>> 40) * 5.9604645E-8f) < 0.02f ? veinType.rawOreBlock : veinType.ore;
				}
				return SharedConstants.DEBUG_ORE_VEINS ? Blocks.OAK_BUTTON.defaultBlockState() : veinType.filler;
			}
			RandomSource rng = randomFactory.at(ctx.blockX(), y, ctx.blockZ());
			if (rng.nextFloat() > 0.7f) {
				return defaultState;
			}
			if (veinRidged.compute(ctx) >= 0.0) {
				return defaultState;
			}
			double richness = Mth.clampedMap(absToggle, 0.4000000059604645, 0.6000000238418579,
				0.10000000149011612, 0.30000001192092896);
			if ((double) rng.nextFloat() < richness && veinGap.compute(ctx) > -0.30000001192092896) {
				return rng.nextFloat() < 0.02f ? veinType.rawOreBlock : veinType.ore;
			}
			return SharedConstants.DEBUG_ORE_VEINS ? Blocks.OAK_BUTTON.defaultBlockState() : veinType.filler;
		};
	}
}
