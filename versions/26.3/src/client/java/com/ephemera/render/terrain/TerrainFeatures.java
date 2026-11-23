package com.ephemera.render.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.GpuDevice;
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
 * Config gating and fail-soft switches for the 26.3 terrain engine.
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
	 * 26.3 regional multi-draw indirect: builds on native {@code MULTIDRAW_TERRAIN} pipelines
	 * (requiring {@code drawIndirect && multiDrawIndirect && nonZeroFirstInstance}) plus optional
	 * Vulkan {@code VK_EXT_mesh_shader} for opaque terrain.
	 */
	public static boolean multiDraw() {
		return !multiDrawFailed && cfg().terrainMultiDraw && batcher() && multiDrawHookSupported() && deviceSupportsMultiDraw();
	}

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

	public static boolean directionalCulling() {
		EphemeraConfig c = cfg();
		return c.terrainEngineEnabled && c.terrainDirectionalFaceCulling;
	}

	public static boolean bakeRegionRelative() {
		EphemeraConfig c = cfg();
		return !bakingFailed && c.terrainEngineEnabled && c.terrainMultiDraw && bakingSupported();
	}

	public static boolean bakeDirectionalBuckets() {
		EphemeraConfig c = cfg();
		return !bakingFailed && c.terrainEngineEnabled && c.terrainDirectionalFaceCulling && bakingSupported();
	}

	public static void disableMultiDraw(Throwable t) {
		if (!multiDrawFailed) {
			multiDrawFailed = true;
			LOGGER.error("Ephemera regional multi-draw failed; falling back to vanilla terrain rendering", t);
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
			LOGGER.error("Ephemera chunk render batcher failed; falling back to vanilla prepareChunkRendersIndirect", t);
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

	static boolean deviceSupportsMultiDraw() {
		GpuDevice device = RenderSystem.getDevice();
		if (device != checkedDevice) {
			DeviceFeatures features = device.getDeviceInfo().features();
			deviceMultiDraw = features.drawIndirect() && features.multiDrawIndirect() && features.nonZeroFirstInstance();
			checkedDevice = device;
			if (!deviceMultiDraw) {
				LOGGER.info("Ephemera regional multi-draw unavailable on {}: drawIndirect={}, multiDrawIndirect={}, nonZeroFirstInstance={}",
					device.getDeviceInfo().backendName(), features.drawIndirect(), features.multiDrawIndirect(), features.nonZeroFirstInstance());
			} else {
				LOGGER.info("Ephemera regional multi-draw active on {} (native 26.3 MULTIDRAW_TERRAIN + region instancing)",
					device.getDeviceInfo().backendName());
			}
		}
		return deviceMultiDraw;
	}

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
