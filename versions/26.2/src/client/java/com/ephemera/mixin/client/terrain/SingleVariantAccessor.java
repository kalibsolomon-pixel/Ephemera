package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the wrapped part of a vanilla {@link SingleVariant} for buried-skip classification. */
@Mixin(SingleVariant.class)
public interface SingleVariantAccessor {
	@Accessor("model")
	BlockStateModelPart ephemera$getModel();
}
