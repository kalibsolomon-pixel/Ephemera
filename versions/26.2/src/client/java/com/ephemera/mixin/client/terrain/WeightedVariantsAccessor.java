package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the variant list of vanilla {@link WeightedVariants} for buried-skip classification. */
@Mixin(WeightedVariants.class)
public interface WeightedVariantsAccessor {
	@Accessor("list")
	WeightedList<BlockStateModel> ephemera$getList();
}
