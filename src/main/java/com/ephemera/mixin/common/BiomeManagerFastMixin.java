package com.ephemera.mixin.common;

import com.ephemera.core.math.FastNoiseMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Bit-exact unrolled 3D Voronoi biome corner search in {@link BiomeManager#getBiome(BlockPos)}:
 * reuses shared X and (X,Y) LCG prefixes across corners and replaces 24 {@code Math.floorMod}
 * divisions with bitwise {@code & 1023L}.
 */
@Mixin(BiomeManager.class)
abstract class BiomeManagerFastMixin {
	@Shadow @Final private BiomeManager.NoiseBiomeSource noiseBiomeSource;
	@Shadow @Final private long biomeZoomSeed;

	/**
	 * @author Ephemera
	 * @reason Bit-exact unrolled Voronoi corner selector with factored LCG prefixes and zero floorMod divisions.
	 */
	@Overwrite
	public Holder<Biome> getBiome(BlockPos pos) {
		int bx = pos.getX() - 2;
		int by = pos.getY() - 2;
		int bz = pos.getZ() - 2;
		int qx = bx >> 2;
		int qy = by >> 2;
		int qz = bz >> 2;
		int best = FastNoiseMath.voronoiBiomeCorner(
			this.biomeZoomSeed, qx, qy, qz,
			(bx & 3) * 0.25, (by & 3) * 0.25, (bz & 3) * 0.25
		);
		return this.noiseBiomeSource.getNoiseBiome(
			(best & 4) == 0 ? qx : qx + 1,
			(best & 2) == 0 ? qy : qy + 1,
			(best & 1) == 0 ? qz : qz + 1
		);
	}
}
