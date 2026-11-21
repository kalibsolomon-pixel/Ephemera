package com.ephemera.mixin;

import com.ephemera.core.config.EphemeraConfig;

/**
 * Client-side fail-soft mixin plugin with stand-down rules for third-party
 * performance/rendering mods when installed alongside Ephemera.
 */
public final class EphemeraClientMixinPlugin extends EphemeraCommonMixinPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		// Terrain engine yields to Sodium when both are installed
		String[] terrainMixins = {
			"FrustumFastMixin",
			"RotatingSectionStorageFastMixin",
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
		for (String m : new String[] {
			"GlCommandEncoderMixin", "GlRenderPassMixin", "GlTextureBindingAccess",
			"GlTextureMixin", "FrameBufferCacheMixin", "SeparateVertexArrayCacheMixin"
		}) {
			registerYield(m, "sodium", "vulkanmod");
		}
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
			"ModelPartCubeFastWriteMixin", "PoseStackPoseAccess", "QuadParticleFastWriteMixin",
			"SheetedDecalFastWriteMixin", "SpriteCoordinateExpanderFastWriteMixin",
			"BakedSheetGlyphAccess", "GlyphEffectAccess"
		}) {
			registerYield(m, "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		}
		registerYield("BakedSheetGlyphFastWriteMixin", "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast", "aurora");

		// Culling, shadows, particles & MoreCulling block states
		registerYield("EntityRendererCacheMixin", "badoptimizations");
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

		// GUI, text & animated item baking (yields font/item-atlas mixins to Aurora Client's SDF font & atlas pipeline)
		registerYield("GuiRenderStateMixin", "immediatelyfast", "modernui", "gnetum");
		registerYield("GuiRenderStateNodeMixin", "immediatelyfast", "modernui", "gnetum");
		registerYield("GuiRendererMixin", "immediatelyfast", "modernui");
		registerYield("FormattedBidiReorderMixin", "modernui", "caxton");
		registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast", "aurora");
		registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast", "aurora");
		registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("AtlasSlotMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("DynamicAtlasAllocatorMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("TrackingItemStackRenderStateMixin", "immediatelyfast", "polytone", "aurora");
		registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone", "aurora");
		registerYield("PatchedDataComponentMapMixin", "immediatelyfast", "gnetum", "polytone", "aurora");
		registerYield("FramerateLimitTrackerMixin", "dynamic_fps");

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
				 "ModelPartCubeFastWriteMixin", "PoseStackPoseAccess", "QuadParticleFastWriteMixin",
				 "SheetedDecalFastWriteMixin", "SpriteCoordinateExpanderFastWriteMixin",
				 "BakedSheetGlyphAccess", "GlyphEffectAccess", "BakedSheetGlyphFastWriteMixin" ->
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
			case "FrustumFastMixin", "RotatingSectionStorageFastMixin" -> cfg.terrainFastFrustumAndGrid;
			case "SectionOcclusionGraphExecutorMixin" -> cfg.terrainAsyncGraphUpdates;

			// Keep terrain, culling, gui, visuals & UI mixins live-toggleable at runtime!
			default -> true;
		};
	}
}
