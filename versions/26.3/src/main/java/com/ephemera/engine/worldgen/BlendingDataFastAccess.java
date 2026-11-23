package com.ephemera.engine.worldgen;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;

/**
 * Direct zero-allocation biome column access on {@code BlendingData} for {@code Blender.blendBiome}.
 */
public interface BlendingDataFastAccess {
	LevelHeightAccessor ephemera$areaWithOldGeneration();

	List<List<Holder<Biome>>> ephemera$biomes();

	int ephemera$cellX(int index);

	int ephemera$cellZ(int index);
}
