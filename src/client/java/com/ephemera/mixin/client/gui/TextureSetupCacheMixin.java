package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.TextureSetupCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.TextureSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caches immutable {@link TextureSetup} records produced by {@code singleTexture} and
 * {@code singleTextureWithLightmap} (measured at ~509 MB / 25s = 5.0% of all Render-thread
 * heap allocation in JFR).
 *
 * <p>Uses {@link WrapOperation} on the {@code NEW TextureSetup} call site (never {@code @Overwrite})
 * so mods like Polytone that modify {@code GameRenderer.lightmap()} inside
 * {@code singleTextureWithLightmap} continue to inject cleanly and their custom lightmap texture
 * view is automatically respected as part of the cache key.
 */
@Mixin(TextureSetup.class)
abstract class TextureSetupCacheMixin {
	@WrapOperation(
		method = "singleTextureWithLightmap(Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)Lnet/minecraft/client/gui/render/TextureSetup;",
		at = @At(
			value = "NEW",
			target = "(Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/textures/GpuSampler;)Lnet/minecraft/client/gui/render/TextureSetup;"
		)
	)
	private static TextureSetup ephemera$cacheSingleWithLightmap(
		GpuTextureView tex0,
		GpuTextureView tex1,
		GpuTextureView tex2,
		GpuSampler sampler0,
		GpuSampler sampler1,
		GpuSampler sampler2,
		Operation<TextureSetup> original
	) {
		if (tex1 == null && sampler1 == null && RenderSystem.isOnRenderThread()) {
			return TextureSetupCache.getOrCreateWithLightmap(tex0, sampler0, tex2, sampler2);
		}
		return original.call(tex0, tex1, tex2, sampler0, sampler1, sampler2);
	}

	@WrapOperation(
		method = "singleTexture(Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)Lnet/minecraft/client/gui/render/TextureSetup;",
		at = @At(
			value = "NEW",
			target = "(Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/textures/GpuSampler;)Lnet/minecraft/client/gui/render/TextureSetup;"
		)
	)
	private static TextureSetup ephemera$cacheSingle(
		GpuTextureView tex0,
		GpuTextureView tex1,
		GpuTextureView tex2,
		GpuSampler sampler0,
		GpuSampler sampler1,
		GpuSampler sampler2,
		Operation<TextureSetup> original
	) {
		if (tex1 == null && tex2 == null && sampler1 == null && sampler2 == null && RenderSystem.isOnRenderThread()) {
			return TextureSetupCache.getOrCreateSingle(tex0, sampler0);
		}
		return original.call(tex0, tex1, tex2, sampler0, sampler1, sampler2);
	}
}
