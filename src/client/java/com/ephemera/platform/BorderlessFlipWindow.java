package com.ephemera.platform;

/** Implemented on {@code Window} by the per-version borderless mixins. */
public interface BorderlessFlipWindow {
	/** Re-applies borderless fullscreen after Borderless Flip Optimization changes. */
	void ephemera$reapplyBorderlessFlip();

	/** Returns true when this window is currently in borderless fullscreen mode. */
	boolean ephemera$isBorderlessFullscreen();

	/** Returns true when this window is currently in exclusive fullscreen mode. */
	boolean ephemera$isExclusiveFullscreen();
}
