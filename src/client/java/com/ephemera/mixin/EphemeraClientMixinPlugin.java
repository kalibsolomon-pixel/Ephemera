package com.ephemera.mixin;

import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Set;

/**
 * Client-side fail-soft mixin plugin with stand-down rules for third-party
 * performance/rendering mods when installed alongside Ephemera.
 */
public final class EphemeraClientMixinPlugin extends EphemeraCommonMixinPlugin {
	// These adapters accelerate Aurora releases that predate ObjectShare fast writes.
	private static final Set<String> LEGACY_AURORA_ADAPTERS = Set.of(
		"AuroraSdfCellMixin", "AuroraSdfInstanceFastMixin", "AuroraWorldLineFastMixin"
	);

	private static boolean auroraHasNativeBridge() {
		return FabricLoader.getInstance().isModLoaded("aurora")
			&& EphemeraClientMixinPlugin.class.getResource("/com/aurora/client/compat/EphemeraBridge.class") != null;
	}

	@Override
	public void onLoad(String mixinPackage) {
		// Terrain engine yields to Sodium when both are installed
		String[] terrainMixins = {
			"BlockModelLighterCacheFastMixin",
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
			"SectionUpdateTrackerFastMixin",
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
			"TerrainUberGpuBufferMixin",
			"VertexSortingFastMixin",
			"VisGraphFastMixin"
		};
		for (String m : terrainMixins) {
			registerYield(m, "sodium");
		}

		// Blaze3D backend dispatch & fast native vertex writers
		registerYield("FrontendRenderPassMixin", "sodium", "vulkanmod");
		registerYield("FrontendRenderPipelineMixin", "sodium", "vulkanmod");
		registerYield("GlCommandEncoderMixin", "sodium", "vulkanmod");
		registerYield("GlRenderPassMixin", "sodium", "vulkanmod");
		registerYield("GlSamplerCacheMixin", "sodium", "vulkanmod");
		registerYield("GlStateManagerCacheMixin", "sodium", "vulkanmod");
		registerYield("GlSurfaceMixin", "vulkanmod");
		registerYield("GlTextureBindingAccess", "sodium", "vulkanmod");
		registerYield("GlTextureMixin", "sodium", "vulkanmod");
		registerYield("FrameBufferCacheMixin", "sodium", "vulkanmod");
		registerYield("SeparateVertexArrayCacheMixin", "sodium", "vulkanmod");
		registerYield("VertexArrayIdAccess", "sodium", "vulkanmod");
		registerYield("GlDeviceNoErrorMixin", "iris", "oculus", "vulkanmod");
		registerYield("MinecraftGlDebugOptionsMixin", "iris", "oculus", "vulkanmod");
		registerYield("WindowGlNoErrorMixin", "iris", "oculus", "vulkanmod");
		registerYield("GlDeviceTerrainPatchMixin", "sodium", "iris", "oculus", "vulkanmod");
		registerYield("GlProgramTerrainBatchMixin", "sodium", "iris", "oculus", "vulkanmod");
		for (String m : new String[] {
			"CommandEncoderBackendAccessor", "RenderPassBackendAccessor",
			"VulkanBackendMixin", "VulkanCommandEncoderMixin", "VulkanGlslCompilerMixin",
			"VulkanGpuSurfaceMixin", "VulkanInstanceMixin", "VulkanQueueMixin", "VulkanTransientMemoryMixin",
			"VulkanRenderPassMixin", "VulkanRenderPassFormatsMixin",
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
		registerYield("BakedSheetGlyphFastWriteMixin", "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		for (String adapter : LEGACY_AURORA_ADAPTERS) {
			registerRequires(adapter, "aurora");
			registerYield(adapter, "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		}

		// Culling, shadows, particles & MoreCulling block states
		registerYield("EntityRendererCacheMixin", "badoptimizations");
		registerYield("EntityRendererFrustumFastMixin", "sodium", "entityculling", "badoptimizations");
		registerYield("LevelExtractorPlayerStateFastMixin", "badoptimizations");
		registerYield("LevelRendererTransientBlockCullMixin", "entityculling");
		registerYield("BlockEntityRendererCacheMixin", "badoptimizations");
		registerYield("ShadowGroundCacheMixin", "badoptimizations");
		registerYield("ShadowFeatureRendererMixin", "badoptimizations");
		registerYield("EntityOcclusionMixin", "entityculling");
		registerYield("BlockEntityOcclusionMixin", "entityculling");
		registerYield("LeafBlockCullingMixin", "moreculling");
		registerYield("LeavesOpaqueFaceMixin", "moreculling");
		registerYield("SignTextCullingMixin", "moreculling");
		registerYield("DecorationCullingMixin", "entityculling", "moreculling");
		registerYield("WeatherCullingMixin", "moreculling");
		registerYield("CloudFaceCullingMixin", "moreculling");
		registerYield("QuadParticleCullingMixin", "particle_core", "badoptimizations");
		registerYield("SingleQuadParticleRotationMixin", "particle_core", "badoptimizations");
		registerYield("ParticleLightmapMixin", "particle_core", "badoptimizations");

		// GUI, text & animated item baking
		registerYield("GuiRenderStateMixin", "immediatelyfast", "modernui", "gnetum");
		registerYield("GuiRenderStateNodeMixin", "immediatelyfast", "modernui", "gnetum");
		registerYield("GuiRendererMixin", "immediatelyfast", "modernui");
		registerYield("FormattedBidiReorderMixin", "modernui", "caxton");
		registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast");
		registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast");
		registerYield("GlyphRenderStateFastMixin", "modernui", "caxton", "immediatelyfast");
		registerYield("GuiRendererTextBatchMixin", "immediatelyfast", "modernui", "caxton");
		registerYield("TextureSetupCacheMixin", "immediatelyfast");
		registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone");
		registerYield("AtlasSlotMixin", "immediatelyfast", "polytone");
		registerYield("DynamicAtlasAllocatorMixin", "immediatelyfast", "polytone");
		registerYield("TrackingItemStackRenderStateMixin", "immediatelyfast", "polytone");
		registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("PatchedDataComponentMapMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("FramerateLimitTrackerMixin", "dynamic_fps");

		// Client memory, lightmap, micro-optimizations & loading
		registerYield("SpriteContentsMixin", "ferritecore");
		registerYield("TextureAtlasMipMixin", "ferritecore");
		registerYield("QuadCollectionDedupMixin", "ferritecore", "modernfix");
		registerYield("ClientChunkCacheFastMixin", "sodium", "lithium", "radium", "canary", "c2me");
		registerYield("ClientChunkCacheStorageMixin", "sodium", "lithium", "radium", "canary", "c2me");
		// Note: ByteBufferBuilderShrinkMixin, MinecraftMemoryGuardMixin, ModelManagerReloadMixin,
		// MinecraftFramePacingMixin, and FramerateLimiterPacingMixin intentionally cooperate alongside
		// Aurora Client (never yielding to Aurora; frame pacing coordinates via ephemera:frame_pacer).
		registerYield("LightmapMixin", "badoptimizations");
		registerYield("LightmapShaderReloadMixin", "badoptimizations");
		registerYield("DebugEntryMixin", "badoptimizations");
		registerYield("SoundListenerMixin", "badoptimizations");
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

		// Window
		registerYield("WindowBorderlessMixin", "borderless-mining", "cubes-without-borders", "fullscreen-windowed");
		registerYield("WindowBorderlessFlipMixin", "borderless-mining", "cubes-without-borders", "fullscreen-windowed");

		// Visuals
		registerYield("EntityRendererLightMixin",
			"lambdynlights", "lambdynlights_api", "dynamiclights", "ryoamiclights", "sodiumdynamiclights", "lucent", "shimmer", "tschipcraft_dynamiclights", "optifabric");
		registerYield("GlobalSettingsUniformMixin",
			"sodium", "vulkanmod", "canvas", "nvidium", "optifabric",
			"lambdynlights", "lambdynlights_api", "dynamiclights", "ryoamiclights", "sodiumdynamiclights", "lucent", "shimmer", "tschipcraft_dynamiclights");
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (LEGACY_AURORA_ADAPTERS.contains(simple) && auroraHasNativeBridge()) {
			MixinHealth.recordStoodDown(simple, "native fast-write bridge");
			return false;
		}
		return super.shouldApplyMixin(targetClassName, mixinClassName);
	}

	@Override
	protected boolean isConfigEnabled(String simpleName, EphemeraConfig cfg) {
		return switch (simpleName) {
			// Backend
			case "FrontendRenderPassMixin", "FrontendRenderPipelineMixin" ->
				cfg.backendGlDispatchCache || cfg.backendVulkanDispatchCache || cfg.terrainVulkanMeshShaders;
			case "GlCommandEncoderMixin", "GlRenderPassMixin", "GlSamplerCacheMixin",
				 "GlStateManagerCacheMixin", "GlTextureBindingAccess",
				 "GlTextureMixin", "FrameBufferCacheMixin", "SeparateVertexArrayCacheMixin",
				 "VertexArrayIdAccess" -> cfg.backendGlDispatchCache;
			case "GlDeviceNoErrorMixin", "MinecraftGlDebugOptionsMixin", "WindowGlNoErrorMixin" -> cfg.backendGlNoErrorContext;
			case "VulkanRenderPassMixin", "VulkanTextureBindingAccess" -> cfg.backendVulkanDispatchCache || cfg.terrainVulkanMeshShaders;
			case "VulkanBackendMixin", "VulkanCommandEncoderMixin", "VulkanGlslCompilerMixin",
				 "VulkanGpuSurfaceMixin", "VulkanQueueMixin", "VulkanTransientMemoryMixin",
				 "VulkanRenderPassFormatsMixin",
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
