package com.ephemera.render.terrain;

import net.minecraft.client.renderer.texture.SpriteContents;

/** Duck interface mixed into {@code SpriteContents.AnimationState}: links a state to its sprite. */
public interface AnimationStateHandle {
	void ephemera$setOwner(SpriteContents owner);
}
