package com.ephemera.mixin.common.worldgen;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips redundant no-op {@code BlockColumn.setBlock(y, state)} calls inside 26.3
 * {@link MaterialSystem#buildSurface} when a surface rule returns the exact same non-fluid
 * {@link BlockState} already present at that block, avoiding unnecessary {@code ProtoChunk#setBlockState},
 * {@code LinearPalette#idFor}, and heightmap updates.
 */
@Mixin(MaterialSystem.class)
abstract class MaterialSystemFastMixin {
	@WrapWithCondition(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/BlockColumn;setBlock(ILnet/minecraft/world/level/block/state/BlockState;)V"
		)
	)
	private boolean ephemera$skipNoOpSurfaceSetBlock(
		BlockColumn column, int y, BlockState newState,
		@Local(ordinal = 0) BlockState currentState
	) {
		return newState != currentState || !newState.getFluidState().isEmpty();
	}
}
