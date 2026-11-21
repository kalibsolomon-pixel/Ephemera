package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiPatchSnapshot;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

@Mixin(PatchedDataComponentMap.class)
abstract class PatchedDataComponentMapMixin implements GuiPatchSnapshot {
	@Shadow private Reference2ObjectMap<DataComponentType<?>, Optional<?>> patch;
	@Shadow private boolean copyOnWrite;

	@Override
	public Object ephemera$patchToken() {
		return patch;
	}

	@Override
	public Object ephemera$armPatchToken() {
		copyOnWrite = true;
		return patch;
	}
}
