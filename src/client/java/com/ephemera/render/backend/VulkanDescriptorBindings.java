package com.ephemera.render.backend;

import com.ephemera.render.backend.RenderPassTextureBinding.Vulkan;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flat slot-indexed uniform &amp; texture binding table for {@link VulkanDescriptorPlan}.
 *
 * <p>Seeded with Minecraft 26.2's hottest descriptor names and dynamically assigns permanent
 * slots (up to {@value #MAX_SLOTS}) to any additional uniform/sampler name encountered in a
 * pipeline layout so {@code VulkanDescriptorPlan.push} avoids {@code HashMap.get} per draw.
 */
public final class VulkanDescriptorBindings {
	static final int MAX_SLOTS = 64;

	private static final String[] HOT_NAMES = {
		"Projection", "DynamicTransforms", "Fog", "Globals", "ChunkSection", "Lighting",
		"Sampler0", "Sampler1", "Sampler2", "Sampler3",
		"CloudInfo", "CloudFaces", "Sky", "DiffuseSampler", "InSampler", "MainSampler"
	};

	private static final ConcurrentHashMap<String, Integer> REGISTRY = new ConcurrentHashMap<>(64);
	private static final String[] SLOT_NAMES = new String[MAX_SLOTS];
	private static final AtomicInteger NEXT_SLOT = new AtomicInteger(HOT_NAMES.length);

	static {
		for (int i = 0; i < HOT_NAMES.length; i++) {
			SLOT_NAMES[i] = HOT_NAMES[i];
			REGISTRY.put(HOT_NAMES[i], i);
		}
	}

	private final GpuBufferSlice[] uniforms = new GpuBufferSlice[MAX_SLOTS];
	private final Vulkan[] textures = new Vulkan[MAX_SLOTS];

	public VulkanDescriptorBindings(Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		// A live config change can enable the cache after vanilla has populated these maps.
		if (!uniforms.isEmpty()) {
			for (Map.Entry<String, GpuBufferSlice> e : uniforms.entrySet()) {
				int slot = slot(e.getKey());
				if (slot >= 0) {
					this.uniforms[slot] = e.getValue();
				}
			}
		}
		if (!textures.isEmpty()) {
			for (Map.Entry<String, ?> e : textures.entrySet()) {
				int slot = slot(e.getKey());
				if (slot >= 0) {
					this.textures[slot] = (Vulkan) e.getValue();
				}
			}
		}
	}

	public static int slot(String name) {
		if (name == null) {
			return -1;
		}
		// Fast identity scan for the 10 hottest vanilla interned literals (covers >99% of calls).
		String[] hot = HOT_NAMES;
		for (int i = 0; i < 10; i++) {
			if (name == hot[i]) {
				return i;
			}
		}
		Integer existing = REGISTRY.get(name);
		if (existing != null) {
			return existing;
		}
		int allocated = NEXT_SLOT.getAndIncrement();
		if (allocated >= MAX_SLOTS) {
			return -1;
		}
		Integer raced = REGISTRY.putIfAbsent(name, allocated);
		if (raced != null) {
			return raced;
		}
		SLOT_NAMES[allocated] = name;
		return allocated;
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
