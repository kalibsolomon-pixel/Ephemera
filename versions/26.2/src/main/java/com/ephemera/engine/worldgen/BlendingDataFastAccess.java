package com.ephemera.engine.worldgen;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;

/** Accessor bridge into {@link net.minecraft.world.level.levelgen.blending.BlendingData}. */
public interface BlendingDataFastAccess {
	LevelHeightAccessor ephemera$areaWithOldGeneration();

	List<List<Holder<Biome>>> ephemera$biomes();

	int ephemera$cellX(int index);

	int ephemera$cellZ(int index);
}
