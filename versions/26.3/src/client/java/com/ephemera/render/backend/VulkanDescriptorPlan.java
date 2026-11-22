package com.ephemera.render.backend;

/**
 * 26.3 has no Ephemera descriptor plan: vanilla's {@code VulkanRenderPass} already binds
 * uniforms by slot index ({@code setUniform(int, Object)}) instead of by name, which is what the
 * 26.2 plan adds. Only the encoder-teardown hook shared with 26.2 remains.
 */
public final class VulkanDescriptorPlan {
	private VulkanDescriptorPlan() {}

	public static void clearRenderThreadCache() {}
}
