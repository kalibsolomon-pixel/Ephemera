package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastBufferState;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface BufferBuilderAccess extends FastBufferState {
	@Accessor("buffer") ByteBufferBuilder ephemera$buffer();
	@Accessor("vertexSize") int ephemera$vertexSize();
	@Accessor("initialElementsToFill") int ephemera$initialElementsToFill();
	@Accessor("elements") VertexFormatElement[] ephemera$elements();
	@Accessor("building") boolean ephemera$isBuilding();
	@Accessor("elementsToFill") int ephemera$elementsToFill();
	@Accessor("elementsToFill") void ephemera$elementsToFill(int mask);
	@Accessor("vertices") int ephemera$vertices();
	@Accessor("vertices") void ephemera$vertices(int count);
	@Accessor("vertexPointer") void ephemera$vertexPointer(long pointer);
}
