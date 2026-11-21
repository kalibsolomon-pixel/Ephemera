package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiAtlasSlotState;
import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(DynamicAtlasAllocator.Slot.class)
abstract class AtlasSlotMixin implements GuiAtlasSlotState {
	@Shadow private DynamicAtlasAllocator.SlotState externalState;

	@Unique private long ephemera$contentVersion;

	@Override
	public long ephemera$contentVersion() {
		return ephemera$contentVersion;
	}

	@Override
	public void ephemera$setContentVersion(long version) {
		ephemera$contentVersion = version;
	}

	@Override
	public void ephemera$markStale() {
		externalState = DynamicAtlasAllocator.SlotState.STALE;
	}
}
