package com.ephemera.render.gui;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Arrays;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Tiny Render-thread direct-mapped cache for immutable {@link TextureSetup} instances.
 * Cleared on resource reloads / world unloads via {@code MemoryLeakGuard} so closed texture
 * views are never retained.
 */
public final class TextureSetupCache {
	private static final int MASK = 15;
	private static final TextureSetup[] SINGLE_CACHE = new TextureSetup[16];
	private static final TextureSetup[] LIGHTMAP_CACHE = new TextureSetup[16];

	private TextureSetupCache() {}

	public static TextureSetup getOrCreateSingle(GpuTextureView tex, GpuSampler sampler) {
		int slot = (System.identityHashCode(tex) * 31 ^ System.identityHashCode(sampler)) & MASK;
		TextureSetup cached = SINGLE_CACHE[slot];
		if (cached != null && cached.texure0() == tex && cached.sampler0() == sampler) {
			return cached;
		}
		TextureSetup created = new TextureSetup(tex, null, null, sampler, null, null);
		SINGLE_CACHE[slot] = created;
		return created;
	}

	public static TextureSetup getOrCreateWithLightmap(
		GpuTextureView tex,
		GpuSampler sampler,
		GpuTextureView lightmap,
		GpuSampler lmSampler
	) {
		int slot = (System.identityHashCode(tex) * 31 ^ System.identityHashCode(sampler)) & MASK;
		TextureSetup cached = LIGHTMAP_CACHE[slot];
		if (cached != null
			&& cached.texure0() == tex
			&& cached.sampler0() == sampler
			&& cached.texure2() == lightmap
			&& cached.sampler2() == lmSampler) {
			return cached;
		}
		TextureSetup created = new TextureSetup(tex, null, lightmap, sampler, null, lmSampler);
		LIGHTMAP_CACHE[slot] = created;
		return created;
	}

	public static void clear() {
		Arrays.fill(SINGLE_CACHE, null);
		Arrays.fill(LIGHTMAP_CACHE, null);
	}
}
