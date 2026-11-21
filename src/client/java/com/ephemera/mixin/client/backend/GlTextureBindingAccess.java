package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.RenderPassTextureBinding;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass$TextureViewAndSampler")
public interface GlTextureBindingAccess extends RenderPassTextureBinding.OpenGl {
	@Accessor("view") GlTextureView ephemera$view();
	@Accessor("sampler") GlSampler ephemera$sampler();
}
