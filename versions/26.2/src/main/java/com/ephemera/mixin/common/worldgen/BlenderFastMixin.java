package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.BlendingDataFastAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates the per-call {@code MutableDouble}, {@code MutableObject}, and two nested capturing
 * lambda allocations inside {@link Blender#blendBiome}, and skips {@code Math.sqrt} unless a cell
 * beats the current best squared distance within {@code HEIGHT_BLENDING_RANGE_CELLS}.
 */
@Mixin(Blender.class)
abstract class BlenderFastMixin {
	@Shadow @Final private static NormalNoise SHIFT_NOISE;
	@Shadow @Final private static int HEIGHT_BLENDING_RANGE_CELLS;
	@Shadow @Final private Long2ObjectOpenHashMap<BlendingData> heightAndBiomeBlendingData;

	/**
	 * @author Ephemera
	 * @reason Zero-allocation fastIterator + squared-distance pruning for biome blending.
	 */
	@Overwrite
	private @Nullable Holder<Biome> blendBiome(int quartX, int quartY, int quartZ) {
		if (this.heightAndBiomeBlendingData.isEmpty()) {
			return null;
		}
		int range = HEIGHT_BLENDING_RANGE_CELLS;
		float rangeSq = (float) range * (float) range;
		float bestDistSq = Float.POSITIVE_INFINITY;
		Holder<Biome> closestBiome = null;

		ObjectIterator<Long2ObjectMap.Entry<BlendingData>> it =
			Long2ObjectMaps.fastIterator(this.heightAndBiomeBlendingData);
		while (it.hasNext()) {
			Long2ObjectMap.Entry<BlendingData> entry = it.next();
			BlendingData data = entry.getValue();
			if (data == null) {
				continue;
			}
			long chunkKey = entry.getLongKey();
			int baseQX = QuartPos.fromSection(ChunkPos.getX(chunkKey));
			int baseQZ = QuartPos.fromSection(ChunkPos.getZ(chunkKey));
			// Coarse chunk AABB reject: all cell offsets lie in [0, 4].
			if (quartX < baseQX - range || quartX > baseQX + 4 + range
				|| quartZ < baseQZ - range || quartZ > baseQZ + 4 + range) {
				continue;
			}
			BlendingDataFastAccess access = (BlendingDataFastAccess) (Object) data;
			LevelHeightAccessor area = access.ephemera$areaWithOldGeneration();
			int minQ = QuartPos.fromBlock(area.getMinY());
			if (quartY < minQ || quartY > QuartPos.fromBlock(area.getMaxY())) {
				continue;
			}
			int relY = quartY - minQ;
			List<List<Holder<Biome>>> columns = access.ephemera$biomes();
			for (int i = 0, n = columns.size(); i < n; i++) {
				List<Holder<Biome>> col = columns.get(i);
				if (col == null) {
					continue;
				}
				Holder<Biome> biome = col.get(relY);
				if (biome == null) {
					continue;
				}
				float dx = (float) (quartX - (baseQX + access.ephemera$cellX(i)));
				float dz = (float) (quartZ - (baseQZ + access.ephemera$cellZ(i)));
				float distSq = dx * dx + dz * dz;
				if (distSq <= rangeSq && distSq < bestDistSq) {
					bestDistSq = distSq;
					closestBiome = biome;
				}
			}
		}

		if (bestDistSq == Float.POSITIVE_INFINITY) {
			return null;
		}
		double closestDist = (double) (float) Math.sqrt(bestDistSq);
		double shift = SHIFT_NOISE.getValue((double) quartX, 0.0, (double) quartZ) * 12.0;
		double blended = Mth.clamp((closestDist + shift) / (double) (range + 1), 0.0, 1.0);
		return blended > 0.5 ? null : closestBiome;
	}
}
