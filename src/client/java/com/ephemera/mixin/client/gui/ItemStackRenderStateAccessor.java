package com.ephemera.mixin.client.gui;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
	@Accessor("layers") ItemStackRenderState.LayerRenderState[] ephemera$layers();

	@Accessor("activeLayerCount") int ephemera$activeLayerCount();
}
