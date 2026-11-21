package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiItemBakingManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiItemAtlas.class)
abstract class GuiItemAtlasMixin {
	@WrapOperation(
		method = "getOrUpdate",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/DynamicAtlasAllocator;getOrAllocate(Ljava/lang/Object;Z)Lnet/minecraft/client/gui/render/DynamicAtlasAllocator$Slot;")
	)
	private DynamicAtlasAllocator.Slot ephemera$keepAnimatedSlots(
		DynamicAtlasAllocator<Object> allocator, Object key, boolean animated,
		Operation<DynamicAtlasAllocator.Slot> original,
		@Local(argsOnly = true) TrackingItemStackRenderState state
	) {
		return GuiItemBakingManager.resolveSlot(animated, state, discard -> original.call(allocator, key, discard));
	}
}
