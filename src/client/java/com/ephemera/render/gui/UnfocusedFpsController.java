package com.ephemera.render.gui;

import net.minecraft.client.Minecraft;

/** Caps the framerate while the game window is not the focused window. */
public final class UnfocusedFpsController {
	private UnfocusedFpsController() {}

	public static int limit(int vanillaLimit, Minecraft minecraft) {
		GuiFeatures features = GuiFeatures.get();
		if (!features.unfocusedFpsLimit || minecraft.isWindowActive()) {
			return vanillaLimit;
		}
		return Math.min(vanillaLimit, features.unfocusedFps);
	}
}
