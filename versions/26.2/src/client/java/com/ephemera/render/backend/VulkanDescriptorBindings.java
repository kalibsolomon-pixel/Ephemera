package com.ephemera.render.backend;

import com.ephemera.render.backend.RenderPassTextureBinding.Vulkan;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.util.Arrays;
import java.util.HashMap;
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
		"DynamicTransforms", "Sampler0", "Projection", "Fog", "Globals", "Sampler2",
		"ChunkSection", "Lighting", "Sampler1", "Sampler3",
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

	private static final int POOL_CAP = 4;
	private static final VulkanDescriptorBindings[] POOL = new VulkanDescriptorBindings[POOL_CAP];
	private static int poolSize;

	private static final int MAP_POOL_CAP = 16;
	private static final HashMap<?, ?>[] MAP_POOL = new HashMap<?, ?>[MAP_POOL_CAP];
	private static int mapPoolSize;

	@SuppressWarnings("unchecked")
	public static <K, V> HashMap<K, V> acquireMap() {
		if (RenderSystem.isOnRenderThread() && mapPoolSize > 0) {
			HashMap<K, V> map = (HashMap<K, V>) MAP_POOL[--mapPoolSize];
			MAP_POOL[mapPoolSize] = null;
			return map;
		}
		return new HashMap<>();
	}

	public static void releaseMap(HashMap<?, ?> map) {
		if (map == null || !RenderSystem.isOnRenderThread()) {
			return;
		}
		if (!map.isEmpty()) {
			map.clear();
		}
		if (mapPoolSize < MAP_POOL_CAP) {
			MAP_POOL[mapPoolSize++] = map;
		}
	}

	private final GpuBufferSlice[] uniforms = new GpuBufferSlice[MAX_SLOTS];
	private final VulkanGpuTextureView[] textureViews = new VulkanGpuTextureView[MAX_SLOTS];
	private final VulkanGpuSampler[] textureSamplers = new VulkanGpuSampler[MAX_SLOTS];

	public static VulkanDescriptorBindings acquire(Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		VulkanDescriptorBindings bindings = null;
		if (RenderSystem.isOnRenderThread() && poolSize > 0) {
			bindings = POOL[--poolSize];
			POOL[poolSize] = null;
		}
		if (bindings == null) {
			return new VulkanDescriptorBindings(uniforms, textures);
		}
		bindings.seed(uniforms, textures);
		return bindings;
	}

	public static void release(VulkanDescriptorBindings bindings) {
		if (bindings == null || !RenderSystem.isOnRenderThread()) {
			return;
		}
		int limit = Math.min(MAX_SLOTS, NEXT_SLOT.get());
		Arrays.fill(bindings.uniforms, 0, limit, null);
		Arrays.fill(bindings.textureViews, 0, limit, null);
		Arrays.fill(bindings.textureSamplers, 0, limit, null);
		if (poolSize < POOL_CAP) {
			POOL[poolSize++] = bindings;
		}
	}

	public VulkanDescriptorBindings(Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		seed(uniforms, textures);
	}

	private void seed(Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
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
				if (slot >= 0 && e.getValue() instanceof Vulkan v) {
					this.textureViews[slot] = v.ephemera$view();
					this.textureSamplers[slot] = v.ephemera$sampler();
				}
			}
		}
	}

	public static int slot(String name) {
		if (name == null) {
			return -1;
		}
		// Fast identity checks for MC 26.2's 6 hottest interned descriptor names.
		if (name == "DynamicTransforms") return 0;
		if (name == "Sampler0") return 1;
		if (name == "Projection") return 2;
		if (name == "Fog") return 3;
		if (name == "Globals") return 4;
		if (name == "Sampler2") return 5;
		String[] hot = HOT_NAMES;
		for (int i = 6; i < hot.length; i++) {
			if (name == hot[i]) {
				return i;
			}
		}
		Integer existing = REGISTRY.get(name);
		if (existing != null) {
			return existing;
		}
		while (true) {
			int cur = NEXT_SLOT.get();
			if (cur >= MAX_SLOTS) {
				REGISTRY.putIfAbsent(name, -1);
				return -1;
			}
			if (NEXT_SLOT.compareAndSet(cur, cur + 1)) {
				Integer raced = REGISTRY.putIfAbsent(name, cur);
				if (raced != null) {
					return raced;
				}
				SLOT_NAMES[cur] = name;
				return cur;
			}
		}
	}

	public GpuBufferSlice uniform(int slot) {
		return uniforms[slot];
	}

	public void uniform(int slot, GpuBufferSlice slice) {
		uniforms[slot] = slice;
	}

	public VulkanGpuTextureView textureView(int slot) {
		return textureViews[slot];
	}

	public VulkanGpuSampler textureSampler(int slot) {
		return textureSamplers[slot];
	}

	public void setTexture(int slot, VulkanGpuTextureView view, VulkanGpuSampler sampler) {
		textureViews[slot] = view;
		textureSamplers[slot] = sampler;
	}

	public void clearTexture(int slot) {
		textureViews[slot] = null;
		textureSamplers[slot] = null;
	}
}
