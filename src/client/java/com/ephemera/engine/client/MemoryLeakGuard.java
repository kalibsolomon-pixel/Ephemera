package com.ephemera.engine.client;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.memory.MemoryPressurePolicy;
import com.ephemera.engine.StateInterning;
import com.ephemera.render.culling.EntityOcclusionManager;
import com.ephemera.render.culling.FastShadowCache;
import com.ephemera.render.culling.ParticleCullingManager;
import com.ephemera.render.gui.GuiItemStateCache;
import com.ephemera.render.gui.TextureSetupCache;
import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.visuals.AnisotropyService;
import com.ephemera.visuals.DynamicLightTracker;

/**
 * Central lifecycle guard that actively prevents world-unload, server-switch, and resource-reload
 * memory leaks across Ephemera's caches and vanilla client hooks.
 *
 * <p>Without explicit teardown on {@code Minecraft.clearClientLevel} and {@code ModelManager.apply},
 * static caches (occlusion level/frustum refs, GUI item state tables holding {@code LocalPlayer} and
 * {@code ClientLevel}, dynamic light level refs, transient model/state interning tables, oversized
 * Vulkan staging rings, and cached GPU samplers) can pin hundreds of megabytes of dead world data.
 */
public final class MemoryLeakGuard {
	private MemoryLeakGuard() {}

	/** Called when leaving a world, switching dimensions/servers, or resetting level render data. */
	public static void onWorldUnload() {
		if (!EphemeraCommon.config().memoryLeakGuard) {
			return;
		}
		EntityOcclusionManager.reset(null);
		ParticleCullingManager.reset();
		FastShadowCache.invalidate();
		GuiItemStateCache.clear();
		TextureSetupCache.clear();
		DynamicLightTracker.clear();
		ChunkRenderBatcher.get().release();
		StateInterning.trim();
		MemoryPressurePolicy.recordWorldUnloadSweep();
	}

	/** Called right after resource-pack model baking completes. */
	public static void onResourceReloadComplete() {
		if (!EphemeraCommon.config().memoryLeakGuard) {
			return;
		}
		ModelInterning.onReloadComplete();
		StateInterning.trim();
		GuiItemStateCache.clear();
		TextureSetupCache.clear();
		AnisotropyService.clearCache();
		MemoryPressurePolicy.recordResourceReloadSweep();
	}
}
