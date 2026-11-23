package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.AnisotropyService;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the block atlas its configured anisotropic sampler every time the atlas is (re)uploaded. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasAnisotropyMixin extends AbstractTexture {
	@Shadow
	public abstract Identifier location();

	@SuppressWarnings("deprecation") // texture location of the block atlas has no replacement constant
	@Inject(method = "upload", at = @At("TAIL"))
	private void ephemera$anisotropicSampler(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(location())) {
			this.sampler = AnisotropyService.resolve(this.sampler);
		}
	}
}
