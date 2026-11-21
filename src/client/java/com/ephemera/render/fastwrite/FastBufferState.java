package com.ephemera.render.fastwrite;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public interface FastBufferState {
	ByteBufferBuilder ephemera$buffer();
	int ephemera$vertexSize();
	int ephemera$initialElementsToFill();
	VertexFormatElement[] ephemera$elements();
	boolean ephemera$isBuilding();
	int ephemera$elementsToFill();
	void ephemera$elementsToFill(int mask);
	int ephemera$vertices();
	void ephemera$vertices(int count);
	void ephemera$vertexPointer(long pointer);

	interface Storage {
		void ephemera$checkOpen();
	}
}
