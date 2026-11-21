package com.ephemera.render.terrain;

/** Duck interface mixed into {@code SpriteContents}: dense id of an animated block-atlas sprite. */
public interface AnimatedSpriteHandle {
	/** Tracker id, or -1 when the sprite is static or lives in another atlas. */
	int ephemera$animId();

	void ephemera$setAnimId(int id);
}
