package com.ephemera.mixin.client.ui;

import com.ephemera.ui.EphemeraSettingsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Opens the Ephemera settings screen wherever vanilla Video Settings would open, so it covers every
 * entry point (Options menu, pause menu, other mods). Only the exact vanilla class is replaced;
 * subclasses and the explicit "vanilla screen" escape hatch pass through.
 */
@Mixin(Gui.class)
public abstract class GuiVideoSettingsMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen ephemera$replaceVideoSettings(Screen screen) {
		if (screen != null && screen.getClass() == VideoSettingsScreen.class && !EphemeraSettingsScreen.openingVanilla()) {
			return new EphemeraSettingsScreen(((OptionsSubScreenAccessor) screen).ephemera$lastScreen());
		}
		return screen;
	}
}
