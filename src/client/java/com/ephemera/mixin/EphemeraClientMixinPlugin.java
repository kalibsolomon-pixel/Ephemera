package com.ephemera.mixin;

import com.ephemera.core.config.EphemeraConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Client-side fail-soft mixin plugin with stand-down rules for third-party
 * performance/rendering mods when installed alongside Ephemera.
 */
public final class EphemeraClientMixinPlugin extends EphemeraCommonMixinPlugin {
	private static Set<String> loadAuroraDeclaredMixins() {
		if (!FabricLoader.getInstance().isModLoaded("aurora")) {
			return Collections.emptySet();
		}
		try (InputStream in = EphemeraClientMixinPlugin.class.getResourceAsStream("/aurora.mixins.json")) {
			if (in == null) {
				return Collections.emptySet();
			}
			JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Set<String> names = new HashSet<>();
			if (obj.has("client")) {
				for (JsonElement el : obj.getAsJsonArray("client")) {
					names.add(el.getAsString());
				}
			}
			return names;
		} catch (Throwable ignored) {
			return Collections.emptySet();
		}
	}

	@Override
	public void onLoad(String mixinPackage) {
		Set<String> auroraMixins = loadAuroraDeclaredMixins();
		// Terrain engine yields to Sodium when both are installed
		String[] terrainMixins = {
			"FrustumFastMixin",
			"LevelExtractorBlockEntityFastMixin",
			"RenderSectionRegionFastMixin",
			"RotatingSectionStorageFastMixin",
			"SectionCopyFastMixin",
			"SectionOcclusionGraphExecutorMixin",
			"TerrainBlockModelFeatureSpriteUseMixin",
			"TerrainChunkSectionsToRenderMixin",
			"TerrainCompiledSectionMeshMixin",
			"TerrainFluidStateModelSetAccessor",
			"TerrainFogRendererAccessor",
			"TerrainItemFeatureSpriteUseMixin",
			"TerrainLevelRendererChunkBatchMixin",
			"TerrainMovingBlockFeatureSpriteUseMixin",
			"TerrainRenderSectionFadeAccessor",
			"TerrainRenderSectionVertexSortingMixin",
			"TerrainSectionCompilerMixin",
			"TerrainSectionCompilerResultsMixin",
			"TerrainSectionRenderDispatcherMixin",
			"TerrainSectionUberBuffersAccessor",
			"TerrainSpriteAnimationStateMixin",
			"TerrainSpriteContentsMixin",
			"TerrainTextureAtlasMixin",
			"TerrainUberGpuBufferHeapAccessor",
			"TerrainUberGpuBufferMixin"
		};
		for (String m : terrainMixins) {
			registerYield(m, "sodium");
		}

		// Blaze3D backend dispatch & fast native vertex writers
		registerYield("GlCommandEncoderMixin", "sodium", "vulkanmod");
		registerYield("GlRenderPassMixin", "sodium", "vulkanmod");
		registerYield("GlTextureBindingAccess", "sodium", "vulkanmod");
		registerYield("GlTextureMixin", auroraMixins.contains("GlTextureMipCacheMixin")
			? new String[] { "sodium", "vulkanmod", "aurora" } : new String[] { "sodium", "vulkanmod" });
		registerYield("FrameBufferCacheMixin", auroraMixins.contains("FrameBufferCacheMixin")
			? new String[] { "sodium", "vulkanmod", "aurora" } : new String[] { "sodium", "vulkanmod" });
		registerYield("SeparateVertexArrayCacheMixin", auroraMixins.contains("VertexArrayCacheSeparateMixin")
			? new String[] { "sodium", "vulkanmod", "aurora" } : new String[] { "sodium", "vulkanmod" });
		registerYield("MinecraftGlDebugOptionsMixin", "iris", "oculus", "vulkanmod");
		registerYield("WindowGlNoErrorMixin", "iris", "oculus", "vulkanmod");
		for (String m : new String[] {
			"CommandEncoderBackendAccessor", "RenderPassBackendAccessor",
			"VulkanBackendMixin", "VulkanCommandEncoderMixin", "VulkanGlslCompilerMixin",
			"VulkanGpuSurfaceMixin", "VulkanQueueMixin", "VulkanTransientMemoryMixin", "VulkanRenderPassMixin",
			"VulkanTextureBindingAccess", "VulkanBufferViewMixin", "VulkanDirectBufferMixin"
		}) {
			registerYield(m, "vulkanmod");
		}
		for (String m : new String[] {
			"BufferBuilderAccess", "ByteBufferBuilderAccess", "BufferBuilderFastWriteMixin",
			"ItemFeatureRendererFastMixin", "ModelPartCubeFastWriteMixin", "PoseStackPoseAccess",
			"QuadParticleFastWriteMixin", "SheetedDecalFastWriteMixin", "SpriteCoordinateExpanderFastWriteMixin",
			"BakedSheetGlyphAccess", "GlyphEffectAccess"
		}) {
			registerYield(m, "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		}
		registerYield("BakedSheetGlyphFastWriteMixin", "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast", "aurora");
		registerRequires("AuroraSdfCellMixin", "aurora");
		registerYield("AuroraSdfCellMixin", "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		registerRequires("AuroraSdfInstanceFastMixin", "aurora");
		registerYield("AuroraSdfInstanceFastMixin", "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		registerRequires("AuroraWorldLineFastMixin", "aurora");
		registerYield("AuroraWorldLineFastMixin", "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");

		// Culling, shadows, particles & MoreCulling block states
		registerYield("EntityRendererCacheMixin", "badoptimizations");
		registerYield("BlockEntityRendererCacheMixin", "badoptimizations");
		registerYield("ShadowGroundCacheMixin", "badoptimizations");
		registerYield("ShadowFeatureRendererMixin", "badoptimizations");
		registerYield("EntityOcclusionMixin", auroraMixins.contains("OcclusionBodyMixin")
			? new String[] { "entityculling", "aurora" } : new String[] { "entityculling" });
		registerYield("BlockEntityOcclusionMixin", auroraMixins.contains("OcclusionChestMixin")
			? new String[] { "entityculling", "aurora" } : new String[] { "entityculling" });
		registerYield("LeafBlockCullingMixin", "moreculling");
		registerYield("LeavesOpaqueFaceMixin", "moreculling");
		registerYield("SignTextCullingMixin", "moreculling");
		registerYield("DecorationCullingMixin", "entityculling", "moreculling");
		registerYield("WeatherCullingMixin", "moreculling");
		registerYield("CloudFaceCullingMixin", "moreculling");
		registerYield("QuadParticleCullingMixin", "particle_core", "badoptimizations");
		registerYield("SingleQuadParticleRotationMixin", "particle_core", "badoptimizations");
		registerYield("ParticleLightmapMixin", "particle_core", "badoptimizations");

		// GUI, text & animated item baking (version-aware yield only for mixins present in the loaded Aurora JAR)
		registerYield("GuiRenderStateMixin", auroraMixins.contains("GuiRenderStateLayeringMixin")
			? new String[] { "immediatelyfast", "modernui", "gnetum", "aurora" }
			: new String[] { "immediatelyfast", "modernui", "gnetum" });
		registerYield("GuiRenderStateNodeMixin", auroraMixins.contains("GuiRenderStateNodeMixin")
			? new String[] { "immediatelyfast", "modernui", "gnetum", "aurora" }
			: new String[] { "immediatelyfast", "modernui", "gnetum" });
		registerYield("GuiRendererMixin", auroraMixins.contains("GuiRendererElementOrderMixin")
			? new String[] { "immediatelyfast", "modernui", "aurora" }
			: new String[] { "immediatelyfast", "modernui" });
		registerYield("FormattedBidiReorderMixin", auroraMixins.contains("FormattedBidiReorderMixin")
			? new String[] { "modernui", "caxton", "aurora" }
			: new String[] { "modernui", "caxton" });
		registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast", "aurora");
		registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast", "aurora");
		registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("AtlasSlotMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("DynamicAtlasAllocatorMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("TrackingItemStackRenderStateMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("PatchedDataComponentMapMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("FramerateLimitTrackerMixin", auroraMixins.contains("FramerateLimitUnfocusedMixin")
			? new String[] { "dynamic_fps", "aurora" } : new String[] { "dynamic_fps" });

		// Client memory, lightmap, micro-optimizations & loading
		registerYield("SpriteContentsMixin", "ferritecore", "aurora");
		registerYield("TextureAtlasMipMixin", "ferritecore", "aurora");
		registerYield("LightmapMixin", "badoptimizations");
		registerYield("LightmapShaderReloadMixin", "badoptimizations");
		registerYield("DebugEntryMixin", "badoptimizations", "aurora");
		registerYield("SoundListenerMixin", "badoptimizations", "aurora");
		registerYield("ToastIdleMixin", "badoptimizations");
		registerYield("TutorialIdleMixin", "badoptimizations");
		registerYield("DebugRendererIdleMixin", "badoptimizations");
		registerYield("FovIdleMixin", "badoptimizations");
		registerYield("LevelLoadDelayMixin", "rrls");
		registerYield("TitleFadeMixin", "rrls");
		registerYield("LoadingFadeMixin", "rrls");
		registerYield("NumericAddressMixin", "fast-ip-ping");

		// Visuals
		registerYield("EntityRendererLightMixin", "lambdynlights", "dynamiclights", "ryoamiclights", "sodiumdynamiclights");
	}

	@Override
	protected boolean isConfigEnabled(String simpleName, EphemeraConfig cfg) {
		return switch (simpleName) {
			// Backend
			case "GlCommandEncoderMixin", "GlRenderPassMixin", "GlTextureBindingAccess",
				 "GlTextureMixin", "FrameBufferCacheMixin", "SeparateVertexArrayCacheMixin" -> cfg.backendGlDispatchCache;
			case "MinecraftGlDebugOptionsMixin", "WindowGlNoErrorMixin" -> cfg.backendGlNoErrorContext;
			case "VulkanRenderPassMixin", "VulkanTextureBindingAccess" -> cfg.backendVulkanDispatchCache || cfg.terrainVulkanMeshShaders;
			case "VulkanBackendMixin", "VulkanCommandEncoderMixin", "VulkanGlslCompilerMixin",
				 "VulkanGpuSurfaceMixin", "VulkanQueueMixin", "VulkanTransientMemoryMixin",
				 "CommandEncoderBackendAccessor", "RenderPassBackendAccessor" ->
				cfg.backendVulkanModernGpu || cfg.terrainVulkanMeshShaders;
			case "VulkanBufferViewMixin", "VulkanDirectBufferMixin" -> cfg.backendVulkanBufferViewCache;
			case "BufferBuilderAccess", "ByteBufferBuilderAccess", "BufferBuilderFastWriteMixin",
				 "ItemFeatureRendererFastMixin", "ModelPartCubeFastWriteMixin", "PoseStackPoseAccess",
				 "QuadParticleFastWriteMixin", "SheetedDecalFastWriteMixin", "SpriteCoordinateExpanderFastWriteMixin",
				 "BakedSheetGlyphAccess", "GlyphEffectAccess", "BakedSheetGlyphFastWriteMixin",
				 "AuroraSdfCellMixin", "AuroraSdfInstanceFastMixin", "AuroraWorldLineFastMixin" ->
				cfg.backendGlDispatchCache || cfg.backendVulkanDispatchCache;

			// Client engine / memory / loading
			case "SpriteContentsMixin", "TextureAtlasMipMixin" -> cfg.memoryFreeSpriteMipmaps;
			case "LightmapMixin", "LightmapShaderReloadMixin" -> cfg.engineLightmapCaching;
			case "DebugEntryMixin" -> cfg.engineDebugHudCache;
			case "SoundListenerMixin" -> cfg.engineSoundListenerThrottle;
			case "ToastIdleMixin", "TutorialIdleMixin", "DebugRendererIdleMixin", "FovIdleMixin" -> cfg.engineIdleShortCircuits;
			case "LevelLoadDelayMixin" -> cfg.loadingSkipLevelLoadDelay;
			case "TitleFadeMixin" -> cfg.loadingSkipTitleFade;
			case "LoadingFadeMixin" -> cfg.loadingNonBlockingReload;
			case "NumericAddressMixin" -> cfg.loadingFastIpPing;
			case "FrustumFastMixin", "RotatingSectionStorageFastMixin", "LevelExtractorBlockEntityFastMixin",
				 "SectionCopyFastMixin", "RenderSectionRegionFastMixin" -> cfg.terrainFastFrustumAndGrid;
			case "SectionOcclusionGraphExecutorMixin" -> cfg.terrainAsyncGraphUpdates;

			// Keep terrain, culling, gui, visuals & UI mixins live-toggleable at runtime!
			default -> true;
		};
	}
}
