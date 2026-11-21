package com.ephemera.mixin.client.ui;

import com.ephemera.ui.EphemeraSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the "Ephemera..." entry button at the end of Video Settings. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	protected VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void ephemera$addSettingsEntry(CallbackInfo ci) {
		this.list.addBig(Button.builder(
			Component.translatable("ephemera.settings.open"),
			button -> this.minecraft.gui.setScreen(new EphemeraSettingsScreen(this))
		).width(Button.BIG_WIDTH).build());
	}
}
