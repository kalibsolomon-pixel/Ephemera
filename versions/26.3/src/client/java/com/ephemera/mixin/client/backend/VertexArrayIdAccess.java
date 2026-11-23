package com.ephemera.mixin.client.backend;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code VertexArray.vertexArrayId} for {@link SeparateVertexArrayCacheMixin}. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.VertexArray")
public interface VertexArrayIdAccess {
	@Accessor("vertexArrayId")
	int ephemera$vertexArrayId();
}
