package com.ephemera.mixin.client.backend;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendRenderPass.class)
public interface RenderPassBackendAccessor {
	@Accessor("backend")
	RenderPassBackend ephemera$backend();
}
