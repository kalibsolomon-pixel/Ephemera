package com.ephemera.render.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.texture.SpriteContents;
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

	private static volatile Boolean batcherSupported;
	private static volatile Boolean spritesSupported;

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
