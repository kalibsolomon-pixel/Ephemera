package com.ephemera.mixin.client.backend;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderPass.class)
public interface RenderPassBackendAccessor {
	@Accessor("backend")
	RenderPassBackend ephemera$backend();
}
