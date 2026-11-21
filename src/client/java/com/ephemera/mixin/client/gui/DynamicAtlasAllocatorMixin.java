package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiAtlasSlotState;
import com.ephemera.render.gui.GuiSlotLookup;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.BitSet;
import java.util.Map;

/**
 * Lets {@code GuiItemAtlas} skip the key-map probe for an item whose slot it already knows. A slot is live
 * while its bit in {@code freeSlots} is clear, and it belongs to the key stamped on it when it was allocated;
 * a slot that was freed and handed to another key fails one of those two checks.
 */
@Mixin(DynamicAtlasAllocator.class)
abstract class DynamicAtlasAllocatorMixin implements GuiSlotLookup {
	@Shadow @Final private int width;
	@Shadow @Final private BitSet freeSlots;

	@WrapOperation(
		method = "getOrAllocate",
		at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
	)
	private Object ephemera$stampOwner(Map<Object, Object> map, Object key, Object slot, Operation<Object> original) {
		if (slot instanceof GuiAtlasSlotState state) {
			state.ephemera$setOwner(key);
		}
		return original.call(map, key, slot);
	}

	@Override
	public DynamicAtlasAllocator.Slot ephemera$revalidate(DynamicAtlasAllocator.Slot slot, Object key, boolean discardAfterFrame) {
		if (!(slot instanceof GuiAtlasSlotState state) || freeSlots.get(slot.x() + slot.y() * width)) {
			return null;
		}
		Object owner = state.ephemera$owner();
		if (owner != key && (owner == null || !owner.equals(key))) {
			return null;
		}
		state.ephemera$touch(discardAfterFrame);
		return slot;
	}
}
