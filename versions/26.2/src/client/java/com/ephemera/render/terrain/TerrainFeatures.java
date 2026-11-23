package com.ephemera.render.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config gating and fail-soft switches for the terrain engine. Every feature checks
 * {@code terrainEngineEnabled} plus its own toggle, and the structural fast paths also
 * verify once that the mixins they depend on were actually applied (a mixin may have
 * stood down for another mod or been disabled with {@code -Dephemera.mixin.disable}).
 */
public final class TerrainFeatures {
	static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/Terrain");

	private static volatile boolean batcherFailed;
	private static volatile boolean compilerFailed;
	private static volatile boolean sortFailed;
	private static volatile boolean spritesFailed;

	private static volatile boolean multiDrawFailed;
	private static volatile boolean bakingFailed;

	private static volatile Boolean batcherSupported;
	private static volatile Boolean spritesSupported;
	private static volatile Boolean bakingSupported;
	private static volatile Boolean multiDrawHookSupported;
	private static volatile @Nullable GpuDevice checkedDevice;
	private static volatile boolean deviceMultiDraw;
	private static volatile boolean deviceDirectMultiDraw;
	/** {@code -Dephemera.terrain.forceDirect=true} selects client-memory multiDrawIndexed even when hardware indirect multi-draw is available. */
	private static final boolean FORCE_DIRECT = Boolean.getBoolean("ephemera.terrain.forceDirect");
	/** {@code -Dephemera.terrain.forceIndirect=true} keeps the indirect-buffer path even if forceDirect is set. */
	private static final boolean FORCE_INDIRECT = Boolean.getBoolean("ephemera.terrain.forceIndirect");
	/** {@code -Dephemera.terrain.planReuse=false} rebuilds the regional plan every frame. */
	private static final boolean PLAN_REUSE = !"false".equals(System.getProperty("ephemera.terrain.planReuse"));
	private static volatile Boolean planReuseSupported;

	private TerrainFeatures() {}

	private static EphemeraConfig cfg() {
		return EphemeraCommon.config();
	}

	public static boolean batcher() {
		return !batcherFailed && cfg().terrainEngineEnabled && batcherSupported();
	}

	public static boolean fogOcclusion() {
		EphemeraConfig c = cfg();
		return c.terrainEngineEnabled && c.terrainFogOcclusion;
	}

	public static boolean bitmaskMeshing() {
		EphemeraConfig c = cfg();
		return !compilerFailed && c.terrainEngineEnabled && c.terrainBitmaskMeshing;
	}

	public static boolean fastTranslucentSort() {
		EphemeraConfig c = cfg();
		return !sortFailed && c.terrainEngineEnabled && c.terrainFastTranslucentSort;
	}

	public static boolean animateOnlyVisible() {
		EphemeraConfig c = cfg();
		return !spritesFailed && c.terrainEngineEnabled && c.terrainAnimateOnlyVisibleTextures && spritesSupported();
	}

	/**
	 * Regional multi-draw: needs the batcher, the {@code ChunkSectionsToRender} render hook,
	 * and a device with either {@code multiDrawDirectSeparate} (OpenGL
	 * {@code glMultiDrawElementsBaseVertex}) or {@code drawIndirect} plus {@code multiDrawIndirect}.
	 */
	public static boolean multiDraw() {
		return !multiDrawFailed && cfg().terrainMultiDraw && batcher() && multiDrawHookSupported() && deviceSupportsMultiDraw();
	}

	/**
	 * Render thread, after {@link #multiDraw()}: true when batches execute as direct
	 * {@code multiDrawIndexed} calls from client memory (no indirect buffer upload).
	 */
	public static boolean directMultiDraw() {
		return deviceDirectMultiDraw;
	}

	/**
	 * Reusing last frame's regional plan when nothing changed needs the mesh-swap hook on
	 * {@code RenderSection} (the other invalidation hooks live in mixins the batcher already requires).
	 */
	public static boolean planReuse() {
		Boolean b = planReuseSupported;
		if (b == null) {
			b = SectionMeshSwapHook.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);
			if (!b) {
				LOGGER.warn("Ephemera terrain frame-plan reuse unavailable: RenderSection mesh-swap hook was not applied");
			}
			planReuseSupported = b;
		}
		return PLAN_REUSE && b;
	}

	/** Skip back-facing directional buckets of meshes that were compiled with them. */
	public static boolean directionalCulling() {
		EphemeraConfig c = cfg();
		return c.terrainEngineEnabled && c.terrainDirectionalFaceCulling;
	}

	/** Compile-time: rebase vertices onto their region origin (read on chunk-builder threads). */
	public static boolean bakeRegionRelative() {
		EphemeraConfig c = cfg();
		return !bakingFailed && c.terrainEngineEnabled && c.terrainMultiDraw && bakingSupported();
	}

	/** Compile-time: reorder opaque quads into directional buckets. */
	public static boolean bakeDirectionalBuckets() {
		EphemeraConfig c = cfg();
		return !bakingFailed && c.terrainEngineEnabled && c.terrainDirectionalFaceCulling && bakingSupported();
	}

	public static void disableMultiDraw(Throwable t) {
		if (!multiDrawFailed) {
			multiDrawFailed = true;
			LOGGER.error("Ephemera regional multi-draw failed; falling back to per-section terrain draws", t);
		}
	}

	public static void disableBaking(Throwable t) {
		if (!bakingFailed) {
			bakingFailed = true;
			LOGGER.error("Ephemera section vertex baking failed; new sections compile with vanilla vertex layout", t);
		}
	}

	public static void disableBatcher(Throwable t) {
		if (!batcherFailed) {
			batcherFailed = true;
			LOGGER.error("Ephemera chunk render batcher failed; falling back to vanilla prepareChunkRenders", t);
		}
	}

	public static void disableCompiler(Throwable t) {
		if (!compilerFailed) {
			compilerFailed = true;
			LOGGER.error("Ephemera section compiler hooks failed; buried-block skipping disabled", t);
		}
	}

	public static void disableSort(Throwable t) {
		if (!sortFailed) {
			sortFailed = true;
			LOGGER.error("Ephemera translucent sorter failed; using vanilla vertex sorting", t);
		}
	}

	public static void disableSprites(Throwable t) {
		if (!spritesFailed) {
			spritesFailed = true;
			LOGGER.error("Ephemera visible-sprite tracker failed; animating every sprite", t);
		}
	}

	/** True when the mesh, uber-buffer and dispatcher ducks are all present. */
	static boolean batcherSupported() {
		Boolean b = batcherSupported;
		if (b == null) {
			b = SectionMeshRenderCache.class.isAssignableFrom(CompiledSectionMesh.class)
				&& UberGpuBufferSlot.class.isAssignableFrom(UberGpuBuffer.class)
				&& SectionDispatcherEpoch.class.isAssignableFrom(SectionRenderDispatcher.class);
			if (!b) {
				LOGGER.warn("Ephemera terrain batcher unavailable: required mixins were not applied");
			}
			batcherSupported = b;
		}
		return b;
	}

	/**
	 * Baked meshes must always be drawn at their baked origin, so baking needs the mesh duck
	 * that carries the layout, the results duck, and the vanilla {@code prepareChunkRenders}
	 * origin fix in case the batcher is ever bypassed.
	 */
	static boolean bakingSupported() {
		Boolean b = bakingSupported;
		if (b == null) {
			b = CompileResultsLayout.class.isAssignableFrom(SectionCompiler.Results.class)
				&& BakedOriginAware.class.isAssignableFrom(LevelRenderer.class)
				&& batcherSupported();
			if (!b) {
				LOGGER.warn("Ephemera section vertex baking unavailable: required mixins were not applied");
			}
			bakingSupported = b;
		}
		return b;
	}

	static boolean multiDrawHookSupported() {
		Boolean b = multiDrawHookSupported;
		if (b == null) {
			b = RegionalRenderHook.class.isAssignableFrom(ChunkSectionsToRender.class);
			if (!b) {
				LOGGER.warn("Ephemera regional multi-draw unavailable: ChunkSectionsToRender hook was not applied");
			}
			multiDrawHookSupported = b;
		}
		return b;
	}

	/** Render thread only. Re-evaluated when the device changes (e.g. a backend switch). */
	static boolean deviceSupportsMultiDraw() {
		GpuDevice device = RenderSystem.getDevice();
		if (device != checkedDevice) {
			DeviceFeatures features = device.getDeviceInfo().features();
			boolean indirect = features.drawIndirect() && features.multiDrawIndirect();
			boolean direct = features.multiDrawDirectSeparate();
			// Prefer hardware multi-draw indirect on both OpenGL and Vulkan: AMD OpenGL emulates
			// glMultiDrawElementsBaseVertex on CPU whereas ARB_multi_draw_indirect runs in hardware.
			deviceDirectMultiDraw = direct && (!indirect || (FORCE_DIRECT && !FORCE_INDIRECT));
			deviceMultiDraw = indirect || direct;
			checkedDevice = device;
			if (!deviceMultiDraw) {
				LOGGER.info("Ephemera regional multi-draw unavailable on {}: multiDrawDirectSeparate={}, drawIndirect={}, multiDrawIndirect={}",
					device.getDeviceInfo().backendName(), direct, features.drawIndirect(), features.multiDrawIndirect());
			} else {
				LOGGER.info("Ephemera regional multi-draw on {}: {}", device.getDeviceInfo().backendName(),
					deviceDirectMultiDraw ? "direct multiDrawIndexed" : "indirect drawIndexedIndirect");
			}
		}
		return deviceMultiDraw;
	}

	/**
	 * True when every hook visible-only animation depends on is present, including the
	 * markers on all three feature renderers that emit baked quads outside terrain. Without
	 * them a held, framed or moving block's animated texture could freeze, so the feature
	 * stays off.
	 */
	static boolean spritesSupported() {
		Boolean b = spritesSupported;
		if (b == null) {
			b = AnimatedSpriteHandle.class.isAssignableFrom(SpriteContents.class)
				&& AnimationStateHandle.class.isAssignableFrom(SpriteContents.AnimationState.class)
				&& CompileResultsSprites.class.isAssignableFrom(SectionCompiler.Results.class)
				&& ExternalSpriteUseHook.class.isAssignableFrom(ItemFeatureRenderer.class)
				&& ExternalSpriteUseHook.class.isAssignableFrom(BlockModelFeatureRenderer.class)
				&& ExternalSpriteUseHook.class.isAssignableFrom(MovingBlockFeatureRenderer.class)
				&& batcherSupported();
			if (!b) {
				LOGGER.warn("Ephemera visible-only texture animation unavailable: required mixins were not applied");
			}
			spritesSupported = b;
		}
		return b;
	}
}
