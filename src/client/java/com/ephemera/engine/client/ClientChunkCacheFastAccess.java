package com.ephemera.engine.client;

import org.jspecify.annotations.Nullable;

/** Duck interface on {@code ClientChunkCache} exposing its fast storage lookup directly. */
public interface ClientChunkCacheFastAccess {
	@Nullable ClientChunkStorageFastLookup ephemera$storageLookup();
}
