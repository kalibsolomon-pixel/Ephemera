package com.ephemera.platform;

/**
 * Implemented by the game window on versions where Ephemera supplies the borderless fullscreen
 * mode itself (26.2; 26.3 has it natively). Lets the settings screen apply a Borderless/Exclusive
 * switch immediately instead of on the next fullscreen toggle.
 */
public interface FullscreenWindow {
	void ephemera$reapplyFullscreen();
}
