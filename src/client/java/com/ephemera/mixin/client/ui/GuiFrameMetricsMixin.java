package com.ephemera.mixin.client.ui;

import com.ephemera.visuals.VisualsDiagnostics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Samples one timestamp per rendered frame (only while the HUD or Diagnostics tab is watching). */
@Mixin(Gui.class)
public abstract class GuiFrameMetricsMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("HEAD"))
	private void ephemera$sampleFrame(DeltaTracker deltaTracker, boolean a, boolean b, CallbackInfo ci) {
		VisualsDiagnostics.onFrame();
	}
}
