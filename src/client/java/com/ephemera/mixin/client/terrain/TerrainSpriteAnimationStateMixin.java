package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.AnimatedSpriteHandle;
import com.ephemera.render.terrain.AnimationStateHandle;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the atlas blit of animated block sprites that no visible section, item or entity
 * uses. Animation timing still advances every tick; a skipped frame change is remembered
 * and drawn as soon as the sprite is in use again, so it never shows a wrong frame for
 * longer than one animation tick.
 */
@Mixin(SpriteContents.AnimationState.class)
public abstract class TerrainSpriteAnimationStateMixin implements AnimationStateHandle {
	@Shadow
	private boolean isDirty;

	@Unique
	private @Nullable SpriteContents ephemera$owner;
	@Unique
	private boolean ephemera$pendingUpload;

	@Override
	public void ephemera$setOwner(SpriteContents owner) {
		ephemera$owner = owner;
	}

	@ModifyReturnValue(method = "needsToDraw", at = @At("RETURN"))
	private boolean ephemera$drawOnlyWhenUsed(boolean needsToDraw) {
		SpriteContents owner = ephemera$owner;
		if (owner == null) {
			return needsToDraw;
		}
		int id = ((AnimatedSpriteHandle) owner).ephemera$animId();
		if (id < 0 || VisibleSpriteTracker.shouldAnimate(id)) {
			return needsToDraw || ephemera$pendingUpload;
		}
		if (needsToDraw) {
			ephemera$pendingUpload = true;
		}
		return false;
	}

	@Inject(method = "drawToAtlas", at = @At("HEAD"))
	private void ephemera$flushPendingUpload(RenderPass renderPass, GpuBufferSlice ubo, CallbackInfo ci) {
		if (ephemera$pendingUpload) {
			ephemera$pendingUpload = false;
			// Forces the blit branch for non-interpolated sprites; tick() clears it again.
			isDirty = true;
		}
	}
}
