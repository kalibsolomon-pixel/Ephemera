package com.ephemera.mixin.client.terrain;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the private {@code SectionRenderDispatcher.SectionUberBuffers} record. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers")
public interface TerrainSectionUberBuffersAccessor {
	@Accessor("vertexBuffer")
	UberGpuBuffer<?> getEphemeraVertexBuffer();

	@Accessor("indexBuffer")
	UberGpuBuffer<?> getEphemeraIndexBuffer();
}
