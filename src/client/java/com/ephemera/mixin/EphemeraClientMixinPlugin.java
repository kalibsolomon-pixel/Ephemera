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

	private static boolean auroraHasNativeBridge() {
		return FabricLoader.getInstance().isModLoaded("aurora")
			&& EphemeraClientMixinPlugin.class.getResource("/com/aurora/client/compat/EphemeraBridge.class") != null;
	}

	@Override
	public void onLoad(String mixinPackage) {
		// When Aurora ships its native EphemeraBridge (3.8+), Aurora yields overlapping engine
		// optimizations to Ephemera and calls ephemera:fast_write directly; older Aurora releases
		// (3.6/3.7) use manifest inspection + Ephemera's @Pseudo compat bridges instead.
		boolean auroraNative = auroraHasNativeBridge();
		Set<String> legacyAuroraMixins = auroraNative ? Collections.emptySet() : loadAuroraDeclaredMixins();
		// Terrain engine yields to Sodium when both are installed
		String[] terrainMixins = {
			"BlockTintCacheFastMixin",
			"ClientLevelTintBindMixin",
			"FrustumFastMixin",
			"LevelExtractorBlockEntityFastMixin",
			"LevelExtractorSectionUpdatesMixin",
			"RenderSectionRegionFastMixin",
			"RotatingSectionStorageFastMixin",
			"SectionCopyFastMixin",
			"SectionDirtyStateEpochMixin",
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
		registerYield("GlTextureMixin", legacyAuroraMixins.contains("GlTextureMipCacheMixin")
			? new String[] { "sodium", "vulkanmod", "aurora" } : new String[] { "sodium", "vulkanmod" });
		registerYield("FrameBufferCacheMixin", legacyAuroraMixins.contains("FrameBufferCacheMixin")
			? new String[] { "sodium", "vulkanmod", "aurora" } : new String[] { "sodium", "vulkanmod" });
		registerYield("SeparateVertexArrayCacheMixin", legacyAuroraMixins.contains("VertexArrayCacheSeparateMixin")
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
		// @Pseudo compat bridges apply only to older Aurora builds without native EphemeraBridge.
		for (String m : new String[] {
			"AuroraSdfCellMixin", "AuroraSdfInstanceFastMixin", "AuroraWorldLineFastMixin"
		}) {
			registerRequires(m, "aurora");
			if (auroraNative) {
				registerYield(m, "aurora");
			} else {
				registerYield(m, "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
			}
		}

		// Culling, shadows, particles & MoreCulling block states
		registerYield("EntityRendererCacheMixin", "badoptimizations");
		registerYield("EntityRendererFrustumFastMixin", "sodium", "entityculling", "badoptimizations");
		registerYield("BlockEntityRendererCacheMixin", "badoptimizations");
		registerYield("ShadowGroundCacheMixin", "badoptimizations");
		registerYield("ShadowFeatureRendererMixin", "badoptimizations");
		registerYield("EntityOcclusionMixin", legacyAuroraMixins.contains("OcclusionBodyMixin")
			? new String[] { "entityculling", "aurora" } : new String[] { "entityculling" });
		registerYield("BlockEntityOcclusionMixin", legacyAuroraMixins.contains("OcclusionChestMixin")
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

		// GUI, text & animated item baking (yields SDF font & item-atlas mixins to Aurora; overlapping engine mixins only for legacy Aurora)
		registerYield("GuiRenderStateMixin", legacyAuroraMixins.contains("GuiRenderStateLayeringMixin")
			? new String[] { "immediatelyfast", "modernui", "gnetum", "aurora" }
			: new String[] { "immediatelyfast", "modernui", "gnetum" });
		registerYield("GuiRenderStateNodeMixin", legacyAuroraMixins.contains("GuiRenderStateNodeMixin")
			? new String[] { "immediatelyfast", "modernui", "gnetum", "aurora" }
			: new String[] { "immediatelyfast", "modernui", "gnetum" });
		registerYield("GuiRendererMixin", legacyAuroraMixins.contains("GuiRendererElementOrderMixin")
			? new String[] { "immediatelyfast", "modernui", "aurora" }
			: new String[] { "immediatelyfast", "modernui" });
		registerYield("FormattedBidiReorderMixin", legacyAuroraMixins.contains("FormattedBidiReorderMixin")
			? new String[] { "modernui", "caxton", "aurora" }
			: new String[] { "modernui", "caxton" });
		registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast", "aurora");
		registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast", "aurora");
		registerYield("GlyphRenderStateFastMixin", "modernui", "caxton", "immediatelyfast");
		registerYield("TextureSetupCacheMixin", "immediatelyfast");
		registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("AtlasSlotMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("DynamicAtlasAllocatorMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("TrackingItemStackRenderStateMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("PatchedDataComponentMapMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("FramerateLimitTrackerMixin", legacyAuroraMixins.contains("FramerateLimitUnfocusedMixin")
			? new String[] { "dynamic_fps", "aurora" } : new String[] { "dynamic_fps" });

		// Client memory, lightmap, micro-optimizations & loading
		registerYield("SpriteContentsMixin", legacyAuroraMixins.contains("TextureAtlasFreeMipsMixin")
			? new String[] { "ferritecore", "aurora" } : new String[] { "ferritecore" });
		registerYield("TextureAtlasMipMixin", legacyAuroraMixins.contains("TextureAtlasFreeMipsMixin")
			? new String[] { "ferritecore", "aurora" } : new String[] { "ferritecore" });
		registerYield("QuadCollectionDedupMixin", "ferritecore", "modernfix");
		registerYield("ClientChunkCacheFastMixin", "sodium", "lithium", "radium", "canary", "c2me");
		registerYield("ClientChunkCacheStorageMixin", "sodium", "lithium", "radium", "canary", "c2me");
		// Note: ByteBufferBuilderShrinkMixin, MinecraftMemoryGuardMixin, ModelManagerReloadMixin,
		// MinecraftFramePacingMixin, and FramerateLimiterPacingMixin intentionally cooperate alongside
		// Aurora Client (never yielding to Aurora; frame pacing coordinates via ephemera:frame_pacer).
		registerYield("LightmapMixin", "badoptimizations");
		registerYield("LightmapShaderReloadMixin", "badoptimizations");
		registerYield("DebugEntryMixin", legacyAuroraMixins.contains("DebugScreenEntryCacheMixin")
			? new String[] { "badoptimizations", "aurora" } : new String[] { "badoptimizations" });
		registerYield("SoundListenerMixin", legacyAuroraMixins.contains("SoundEngineListenerMixin")
			? new String[] { "badoptimizations", "aurora" } : new String[] { "badoptimizations" });
		registerYield("ToastIdleMixin", "badoptimizations");
		registerYield("TutorialIdleMixin", "badoptimizations");
		registerYield("DebugRendererIdleMixin", "badoptimizations");
		registerYield("FovIdleMixin", "badoptimizations");
		registerYield("LevelLoadDelayMixin", "rrls", "fastload");
		registerYield("TitleFadeMixin", "rrls", "fastload");
		registerYield("LoadingFadeMixin", "rrls", "fastload");
		registerYield("NumericAddressMixin", "fast-ip-ping");
		registerYield("ResolvedServerAddressFastMixin", "fast-ip-ping");
		registerYield("ServerNameResolverFastMixin", "fast-ip-ping");
		registerYield("ScreenEffectRendererFastMixin", "badoptimizations");

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
			case "ClientChunkCacheFastMixin", "ClientChunkCacheStorageMixin" -> cfg.engineFastChunkLookup;
			case "QuadCollectionDedupMixin" -> cfg.memoryDeduplicateModels;
			case "ByteBufferBuilderShrinkMixin" -> cfg.memoryTrimNativeBuffers;
			case "MinecraftMemoryGuardMixin", "ModelManagerReloadMixin" -> cfg.memoryLeakGuard;
			case "LightmapMixin", "LightmapShaderReloadMixin" -> cfg.engineLightmapCaching;
			case "DebugEntryMixin" -> cfg.engineDebugHudCache;
			case "SoundListenerMixin" -> cfg.engineSoundListenerThrottle;
			case "ToastIdleMixin", "TutorialIdleMixin", "DebugRendererIdleMixin", "FovIdleMixin" -> cfg.engineIdleShortCircuits;
			case "LevelLoadDelayMixin" -> cfg.loadingSkipLevelLoadDelay;
			case "TitleFadeMixin" -> cfg.loadingSkipTitleFade;
			case "LoadingFadeMixin" -> cfg.loadingNonBlockingReload;
			case "NumericAddressMixin", "ResolvedServerAddressFastMixin", "ServerNameResolverFastMixin" -> cfg.loadingFastIpPing;
			case "FrustumFastMixin", "RotatingSectionStorageFastMixin", "LevelExtractorBlockEntityFastMixin",
				 "EntityRendererFrustumFastMixin", "SectionCopyFastMixin", "RenderSectionRegionFastMixin" -> cfg.terrainFastFrustumAndGrid;
			case "SectionOcclusionGraphExecutorMixin" -> cfg.terrainAsyncGraphUpdates;

			// Keep terrain, culling, gui, visuals & UI mixins live-toggleable at runtime!
			default -> true;
		};
	}
}
