package com.ephemera.render.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Direct primitive-coordinate block &amp; block-entity access on immutable {@code SectionCopy}
 * snapshots used during chunk meshing.
 */
public interface SectionCopyFastAccess {
	BlockState ephemera$getBlockState(int x, int y, int z);

	@Nullable BlockEntity ephemera$getBlockEntityFast(BlockPos pos);
}
