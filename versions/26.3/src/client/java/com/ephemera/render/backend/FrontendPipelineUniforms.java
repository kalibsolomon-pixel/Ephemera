package com.ephemera.render.backend;

/**
 * Duck interface on 26.3 {@code FrontendRenderPipeline} exposing a tiny L1-resident flat uniform
 * slot table so {@code FrontendRenderPass} avoids {@code Object2IntOpenHashMap.getOrDefault} and
 * {@code HashMap.forEach} on every uniform bind and pipeline switch.
 */
public interface FrontendPipelineUniforms {
	String[] ephemera$uniformNames();

	int[] ephemera$uniformSlots();

	int ephemera$findSlot(String name);
}
