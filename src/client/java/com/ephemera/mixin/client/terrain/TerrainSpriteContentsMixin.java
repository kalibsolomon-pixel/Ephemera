package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.AnimatedSpriteHandle;
import com.ephemera.render.terrain.AnimationStateHandle;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Stores the tracker id on sprite contents and links each animation state back to its sprite. */
@Mixin(SpriteContents.class)
public abstract class TerrainSpriteContentsMixin implements AnimatedSpriteHandle {
	@Unique
	private int ephemera$animId = -1;

	@Override
	public int ephemera$animId() {
		return ephemera$animId;
	}

	@Override
	public void ephemera$setAnimId(int id) {
		ephemera$animId = id;
	}

	@ModifyReturnValue(method = "createAnimationState", at = @At("RETURN"))
	private SpriteContents.@Nullable AnimationState ephemera$linkOwner(SpriteContents.@Nullable AnimationState state) {
		if (state instanceof AnimationStateHandle handle) {
			handle.ephemera$setOwner((SpriteContents) (Object) this);
		}
		return state;
	}
}
