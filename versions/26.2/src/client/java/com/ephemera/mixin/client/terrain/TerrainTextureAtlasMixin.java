package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.TerrainFeatures;
import com.ephemera.render.terrain.VisibleSpriteTracker;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Assigns dense tracker ids to the block atlas's animated sprites whenever it (re)uploads. */
@Mixin(TextureAtlas.class)
public abstract class TerrainTextureAtlasMixin {
	@Shadow
	private List<TextureAtlasSprite> sprites;

	@Shadow
	@Final
	private Identifier location;

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
	}
}
