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
			"TerrainBlockModelFeatureSpriteUseMixin",
			"TerrainCompiledSectionMeshMixin",
			"TerrainFluidStateModelSetAccessor",
			"TerrainFogRendererAccessor",
			"TerrainItemFeatureSpriteUseMixin",
			"TerrainLevelRendererChunkBatchMixin",
			"TerrainMovingBlockFeatureSpriteUseMixin",
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
			"VulkanRenderPassMixin", "VulkanTextureBindingAccess",
			"VulkanBufferViewMixin", "VulkanDirectBufferMixin"
		}) {
			registerYield(m, "vulkanmod");
		}
		for (String m : new String[] {
			"BufferBuilderAccess", "ByteBufferBuilderAccess", "BufferBuilderFastWriteMixin",
			"ModelPartCubeFastWriteMixin", "QuadParticleFastWriteMixin",
			"BakedSheetGlyphAccess", "GlyphEffectAccess", "BakedSheetGlyphFastWriteMixin"
		}) {
			registerYield(m, "sodium", "iris", "oculus", "vulkanmod", "immediatelyfast");
		}

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

		// GUI, text & animated item baking
		registerYield("GuiRenderStateMixin", "immediatelyfast", "modernui", "gnetum");
		registerYield("GuiRendererMixin", "immediatelyfast", "modernui");
		registerYield("FormattedBidiReorderMixin", "modernui", "caxton");
		registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast");
		registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast");
		registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone");
		registerYield("AtlasSlotMixin", "immediatelyfast", "polytone");
		registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone");
		registerYield("FramerateLimitTrackerMixin", "dynamic_fps");

		// Client memory, lightmap, micro-optimizations & loading
		registerYield("SpriteContentsMixin", "ferritecore");
		registerYield("TextureAtlasMipMixin", "ferritecore");
		registerYield("LightmapMixin", "badoptimizations");
		registerYield("LightmapShaderReloadMixin", "badoptimizations");
		registerYield("DebugEntryMixin", "badoptimizations");
		registerYield("SoundListenerMixin", "badoptimizations");
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
			case "VulkanRenderPassMixin", "VulkanTextureBindingAccess" -> cfg.backendVulkanDispatchCache;
			case "VulkanBufferViewMixin", "VulkanDirectBufferMixin" -> cfg.backendVulkanBufferViewCache;
			case "BufferBuilderAccess", "ByteBufferBuilderAccess", "BufferBuilderFastWriteMixin",
				 "ModelPartCubeFastWriteMixin", "QuadParticleFastWriteMixin",
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

			// Keep terrain, culling, gui, visuals & UI mixins live-toggleable at runtime!
			default -> true;
		};
	}
}
