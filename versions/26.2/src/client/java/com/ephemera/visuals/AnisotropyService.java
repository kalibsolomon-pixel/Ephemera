package com.ephemera.visuals;

import com.ephemera.EphemeraClient;
import com.ephemera.mixin.client.visuals.AbstractTextureAccessor;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.HashMap;
import java.util.Map;

/**
 * Swaps the block atlas sampler for one with configurable anisotropic filtering. The sampler is
 * created through Blaze3D, so the same code path serves OpenGL and native Vulkan; the requested
 * level is clamped to what the device reports. Samplers are cached per variant for the session.
 */
public final class AnisotropyService {
	private static final Map<String, GpuSampler> CACHE = new HashMap<>();
	private static GpuDevice cacheDevice;
	private static GpuSampler activeSampler;
	private static boolean failureLogged;

	private AnisotropyService() {}

	/** Sampler the block atlas should use right now, given the one it currently has. */
	public static GpuSampler resolve(GpuSampler current) {
		try {
			int requested = VisualsRuntime.anisotropy();
			GpuDevice device = RenderSystem.tryGetDevice();
			if (device == null || current == null) {
				return current;
			}
			if (device != cacheDevice) {
				CACHE.clear();
				activeSampler = null;
				cacheDevice = device;
			}
			if (requested <= 1 || device.getDeviceInfo().hintsAndWorkarounds().anisotropyHasKnownIssues()) {
				activeSampler = null;
				return vanilla();
			}
			int level = Math.max(1, Math.min(requested, device.getDeviceInfo().limits().maxAnisotropy()));
			if (level <= 1) {
				activeSampler = null;
				return vanilla();
			}
			// Anisotropy only engages on a filtered, mipmapped minification path, so minify linearly
			// while keeping magnification nearest for crisp close-up texels.
			String key = level + "|" + current.getAddressModeU() + "|" + current.getAddressModeV() + "|" + current.getMagFilter() + "|" + current.getMaxLod();
			GpuSampler cached = CACHE.get(key);
			if (cached == null || isSamplerClosed(cached)) {
				cached = device.createSampler(
					current.getAddressModeU(),
					current.getAddressModeV(),
					FilterMode.LINEAR,
					current.getMagFilter(),
					level,
					current.getMaxLod()
				);
				CACHE.put(key, cached);
			}
			activeSampler = cached;
			return cached;
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				EphemeraClient.LOGGER.warn("Anisotropic filtering unavailable, keeping vanilla sampler: {}", e.toString());
			}
			return current;
		}
	}

	/** Re-evaluates the block atlas sampler immediately (used after a settings change). */
	@SuppressWarnings("deprecation") // texture location of the block atlas has no replacement constant
	public static void refreshBlockAtlas() {
		Minecraft mc = Minecraft.getInstance();
		AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		if (atlas == null || atlas.getSampler() == null) {
			return;
		}
		GpuSampler resolved = resolve(atlas.getSampler());
		if (resolved != atlas.getSampler()) {
			((AbstractTextureAccessor) atlas).ephemera$setSampler(resolved);
		}
	}

	/**
	 * Closes and drops retired GPU samplers while preserving any sampler currently bound to the
	 * live block atlas (AtlasManager uploads before ModelManager.apply triggers resource cleanup).
	 */
	@SuppressWarnings("deprecation")
	public static void clearCache() {
		if (CACHE.isEmpty()) {
			return;
		}
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device != cacheDevice) {
			CACHE.clear();
			activeSampler = null;
			cacheDevice = device;
			return;
		}
		GpuSampler live = activeSampler;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.getTextureManager() != null) {
				AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
				if (atlas != null && atlas.getSampler() != null) {
					live = atlas.getSampler();
				}
			}
		} catch (Throwable ignored) {}

		var it = CACHE.entrySet().iterator();
		while (it.hasNext()) {
			GpuSampler sampler = it.next().getValue();
			if (sampler == live || sampler == activeSampler) {
				continue;
			}
			it.remove();
			try {
				if (!isSamplerClosed(sampler)) {
					sampler.close();
				}
			} catch (Throwable ignored) {}
		}
	}

	private static boolean isSamplerClosed(GpuSampler sampler) {
		return sampler instanceof GlSampler gl && gl.isClosed();
	}

	private static GpuSampler vanilla() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
	}
}
