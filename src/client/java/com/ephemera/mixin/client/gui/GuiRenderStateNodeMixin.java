package com.ephemera.mixin.client.gui;

import com.ephemera.core.gui.IndexedRectList;
import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.gui.GuiLayerIndex;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;

/**
 * Creates the lazily allocated per-layer lists that {@code GuiRenderState.hasIntersection} scans
 * (elements, items, text, picture-in-picture) as {@link IndexedRectList}s, so each list carries its own
 * bounds index and layer queries need no side table. {@code glyphStates} is never queried and stays a plain list.
 */
@Mixin(targets = "net.minecraft.client.renderer.state.gui.GuiRenderState$Node")
abstract class GuiRenderStateNodeMixin {
	@SuppressWarnings({"rawtypes", "unchecked"})
	@WrapOperation(
		method = {"addItem", "addText", "addPicturesInPictureState", "addGuiElement"},
		at = @At(value = "NEW", target = "java/util/ArrayList")
	)
	private ArrayList ephemera$indexedLayerList(Operation<ArrayList> original) {
		if (GuiFeatures.get().layerIndexing) {
			return new IndexedRectList<ScreenArea>(GuiLayerIndex.AREA_BOUNDS);
		}
		return original.call();
	}
}
