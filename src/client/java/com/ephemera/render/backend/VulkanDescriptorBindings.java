package com.ephemera.render.backend;

import com.ephemera.render.backend.RenderPassTextureBinding.Vulkan;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.Map;

public final class VulkanDescriptorBindings {
	private static final String[] NAMES = {
		"Projection", "DynamicTransforms", "Fog", "Globals", "ChunkSection", "Lighting",
		"Sampler0", "Sampler1", "Sampler2"
	};

	private final GpuBufferSlice[] uniforms = new GpuBufferSlice[NAMES.length];
	private final Vulkan[] textures = new Vulkan[NAMES.length];

	public VulkanDescriptorBindings(Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		// A live config change can enable the cache after vanilla has populated these maps.
		if (!uniforms.isEmpty()) {
			for (int slot = 0; slot < NAMES.length; slot++) {
				this.uniforms[slot] = uniforms.get(NAMES[slot]);
			}
		}
		if (!textures.isEmpty()) {
			for (int slot = 0; slot < NAMES.length; slot++) {
				texture(slot, textures.get(NAMES[slot]));
			}
		}
	}

	public static int slot(String name) {
		for (int slot = 0; slot < NAMES.length; slot++) {
			if (name == NAMES[slot]) {
				return slot;
			}
		}
		// Names can come from reflection or another mod as equal, non-interned strings.
		for (int slot = 0; slot < NAMES.length; slot++) {
			if (NAMES[slot].equals(name)) {
				return slot;
			}
		}
		return -1;
	}

	public GpuBufferSlice uniform(int slot) {
		return uniforms[slot];
	}

	public void uniform(int slot, GpuBufferSlice slice) {
		uniforms[slot] = slice;
	}

	public Vulkan texture(int slot) {
		return textures[slot];
	}

	public void texture(int slot, Object binding) {
		textures[slot] = (Vulkan) binding;
	}
}
