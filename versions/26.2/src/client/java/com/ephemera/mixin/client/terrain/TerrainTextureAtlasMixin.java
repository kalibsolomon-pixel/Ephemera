package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.TerrainFeatures;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Assigns dense tracker ids to the block atlas's animated sprites on upload, caches the atlas
 * tick timestamp once per tick, and collects dirty animation states once per tick instead of
 * scanning all animated sprites across every mip level (and allocating {@code Stream.anyMatch}).
 */
@Mixin(TextureAtlas.class)
public abstract class TerrainTextureAtlasMixin {
	@Shadow
	private List<TextureAtlasSprite> sprites;

	@Shadow
	private List<SpriteContents.AnimationState> animatedTexturesStates;

	@Shadow
	private int maxMipLevel;

	@Shadow
	private GpuTextureView[] mipViews;

	@Shadow
	@Final
	private Identifier location;

	@Unique
	private SpriteContents.AnimationState[] ephemera$dirtyStates = new SpriteContents.AnimationState[16];

	@Unique
	private Supplier<String> ephemera$passLabel;

	@Inject(method = "upload", at = @At("RETURN"))
	private void ephemera$indexAnimatedSprites(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(location)) {
			try {
				VisibleSpriteTracker.onBlockAtlasUpload(sprites);
			} catch (Throwable t) {
				TerrainFeatures.disableSprites(t);
			}
		}
	}

	@Inject(method = "clearTextureData", at = @At("HEAD"))
	private void ephemera$forgetAnimatedSprites(CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(location)) {
			VisibleSpriteTracker.onBlockAtlasCleared();
		}
		SpriteContents.AnimationState[] arr = this.ephemera$dirtyStates;
		for (int i = 0; i < arr.length; i++) {
			arr[i] = null;
		}
	}

	@Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
	private void ephemera$beginAtlasTick(CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(location)) {
			VisibleSpriteTracker.beginAtlasTick();
		}
	}

	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastUploadAnimationFrames(CallbackInfo ci) {
		List<SpriteContents.AnimationState> states = this.animatedTexturesStates;
		int n = states.size();
		if (n == 0) {
			ci.cancel();
			return;
		}
		SpriteContents.AnimationState[] dirty = this.ephemera$dirtyStates;
		int dirtyCount = 0;
		for (int i = 0; i < n; i++) {
			SpriteContents.AnimationState st = states.get(i);
			if (st.needsToDraw()) {
				if (dirtyCount == dirty.length) {
					dirty = this.ephemera$dirtyStates = java.util.Arrays.copyOf(dirty, dirty.length * 2);
				}
				dirty[dirtyCount++] = st;
			}
		}
		if (dirtyCount == 0) {
			ci.cancel();
			return;
		}
		GpuTextureView[] views = this.mipViews;
		if (views == null) {
			return;
		}
		Supplier<String> label = this.ephemera$passLabel;
		if (label == null) {
			String s = "Sprite upload for " + this.location;
			label = this.ephemera$passLabel = () -> s;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		int maxMip = this.maxMipLevel;
		for (int mip = 0; mip <= maxMip; mip++) {
			try (RenderPass pass = encoder.createRenderPass(label, views[mip], Optional.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				for (int i = 0; i < dirtyCount; i++) {
					SpriteContents.AnimationState st = dirty[i];
					st.drawToAtlas(pass, st.getDrawUbo(mip));
				}
			}
		}
		for (int i = 0; i < dirtyCount; i++) {
			dirty[i] = null;
		}
		ci.cancel();
	}
}
