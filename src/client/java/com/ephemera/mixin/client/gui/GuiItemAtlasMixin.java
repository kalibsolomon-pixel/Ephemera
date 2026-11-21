package com.ephemera.mixin.client.gui;

import com.ephemera.core.gui.ModelIdentityKey;
import com.ephemera.render.gui.GuiItemBakingManager;
import com.ephemera.render.gui.GuiSlotLookup;
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
		int plan = GuiItemBakingManager.plan(animated, state);
		boolean discard = GuiItemBakingManager.discardAfterFrame(plan, animated);
		DynamicAtlasAllocator.Slot slot = null;
		// the same state is drawn every frame of a tick: its key remembers the slot, so a still-valid slot
		// needs neither the key hash nor the map probe
		ModelIdentityKey memo = key instanceof ModelIdentityKey identity ? identity : null;
		if (memo != null && allocator instanceof GuiSlotLookup lookup
			&& memo.memoFor(allocator) instanceof DynamicAtlasAllocator.Slot remembered) {
			slot = lookup.ephemera$revalidate(remembered, key, discard);
		}
		if (slot == null) {
			slot = original.call(allocator, key, discard);
			if (memo != null && slot != null) {
				memo.remember(allocator, slot);
			}
		}
		GuiItemBakingManager.afterLookup(plan, slot);
		return slot;
	}
}
