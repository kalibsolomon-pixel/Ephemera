package com.ephemera.mixin.common.worldgen;

import com.ephemera.engine.worldgen.BlendingDataFastAccess;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes {@link BlendingData}'s biome columns and cell offsets to {@link BlenderFastMixin}. */
@Mixin(BlendingData.class)
abstract class BlendingDataFastMixin implements BlendingDataFastAccess {
	@Shadow @Final private LevelHeightAccessor areaWithOldGeneration;
	@Shadow @Final private List<List<Holder<Biome>>> biomes;

	@Shadow
	private static int getX(int index) {
		throw new AssertionError();
	}

	@Shadow
	private static int getZ(int index) {
		throw new AssertionError();
	}

	@Override
	public LevelHeightAccessor ephemera$areaWithOldGeneration() {
		return this.areaWithOldGeneration;
	}

	@Override
	public List<List<Holder<Biome>>> ephemera$biomes() {
		return this.biomes;
	}

	@Override
	public int ephemera$cellX(int index) {
		return getX(index);
	}

	@Override
	public int ephemera$cellZ(int index) {
		return getZ(index);
	}
}
