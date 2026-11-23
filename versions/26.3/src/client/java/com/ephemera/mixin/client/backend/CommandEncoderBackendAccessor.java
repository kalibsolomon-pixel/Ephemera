package com.ephemera.mixin.client.backend;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendCommandEncoder.class)
public interface CommandEncoderBackendAccessor {
	@Accessor("backend")
	CommandEncoderBackend ephemera$backend();
}
