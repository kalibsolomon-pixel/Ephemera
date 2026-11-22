package com.ephemera.core.config;

/**
 * Complete configuration schema for Ephemera.
 * Machine-scoped performance settings and cosmetic/visual settings are cleanly
 * separated so Aurora Client can later map profile-scoped visuals separately
 * from hardware-scoped engine toggles.
 */
public final class EphemeraConfig {
	public int schemaVersion = 1;

	// --- Terrain Rendering Engine ---
	public boolean terrainEngineEnabled = true;
	public boolean terrainMultiDraw = true;
	public boolean terrainDirectionalFaceCulling = true;
	public boolean terrainBitmaskMeshing = true;
	public boolean terrainCompactVertexFormat = true;
	public boolean terrainFogOcclusion = true;
	public boolean terrainAsyncGraphUpdates = true;
	public boolean terrainAnimateOnlyVisibleTextures = true;
	public boolean terrainFastTranslucentSort = true;
	public boolean terrainPrefixSumBiomeBlend = true;
	public boolean terrainVulkanMeshShaders = true;
	public boolean terrainVulkanGpuCulling = true;
	public boolean terrainFastFrustumAndGrid = true;
	public int chunkBuilderThreads = 0; // 0 = auto tuned to CPU topology
	public int maxSectionUploadsPerFrameMs = 4;

	// --- Backend Dispatch Optimization (OpenGL + Native Vulkan) ---
	public boolean backendGlDispatchCache = true;
	public boolean backendGlNoErrorContext = true;
	public boolean backendVulkanDispatchCache = true;
	public boolean backendVulkanBufferViewCache = true;
	public boolean backendVulkanModernGpu = true;

	// --- Entity, Block Entity & Block State Culling ---
	public boolean entityOcclusionCulling = true;
	public boolean blockEntityOcclusionCulling = true;
	public boolean entityRendererDirectCache = true;
	public boolean blockEntityRendererDirectCache = true;
	public boolean blockStateCulling = true;
	public LeavesCullingMode leavesCullingMode = LeavesCullingMode.DEPTH;
	public int leavesCullingDepth = 2;
	public boolean signTextBackfaceCulling = true;
	public boolean itemFrameCulling = true;
	public boolean paintingBackfaceCulling = true;
	public boolean weatherCulling = true;
	public boolean cloudFaceCulling = true;

	// --- GUI, HUD, Text & Immediate-Mode ---
	public boolean guiLayerIndexing = true;
	public boolean guiFastLtrText = true;
	public boolean guiMergeTextDecorations = true;
	public boolean guiFontAtlasResizing = true;
	public int guiFontAtlasSize = 1024;
	public boolean guiAnimatedItemBaking = true;
	public boolean guiGlintRateLimit = true;
	public int guiGlintFps = 60;
	public boolean guiSkipTextTranslucencySort = true;
	public boolean guiHudFrameCache = false; // opt-in

	// --- Particles ---
	public boolean particleCulling = true;
	public boolean particleOcclusionCulling = true;
	public boolean particleLightmapCache = true;
	public boolean particleAsyncTick = false;
	public int maxParticlesPerSheet = 16384;

	// --- Memory, Loading & Engine (FerriteCore / Lithium / ScalableLux / Krypton / BadOpt / RRLS) ---
	public boolean memoryCompactBlockStates = true;
	public boolean memoryFreeSpriteMipmaps = true;
	public boolean memoryDeduplicateShapes = true;
	public boolean memoryDeduplicateModels = true;
	public boolean memoryLeakGuard = true;
	public boolean memoryTrimNativeBuffers = true;
	public boolean loadingNonBlockingReload = true;
	public boolean loadingSkipTitleFade = true;
	public boolean loadingSkipLevelLoadDelay = true;
	public boolean loadingFastIpPing = true;
	public boolean engineFastMath = true;
	public boolean engineFastLighting = true;
	public boolean engineFastChunkLookup = true;
	public boolean engineFastCollisions = true;
	public boolean engineFastEntityLookup = true;
	public boolean engineFastPalettedStorage = true;
	public boolean engineFastWorldgenNoise = true;
	public boolean engineFastBiomeLookup = true;
	public boolean engineWorkerPriorityIsolation = true;
	public boolean engineLightmapCaching = true;
	public boolean engineSoundListenerThrottle = true;
	public boolean engineDebugHudCache = true;
	public boolean engineIdleShortCircuits = true;
	public boolean networkFlushConsolidation = true;
	public boolean networkFastVarInt = true;

	// --- Frame Pacing & Consistency (cooperates automatically with Aurora Client) ---
	public boolean framePacerEnabled = true;
	public boolean framePacerLowLatency = true;
	public boolean framePacerAdaptiveUploadBudget = true;
	public int framePacerSpinTailMicros = 500;
	public boolean unfocusedFpsLimitEnabled = false;
	public int unfocusedFpsLimit = 30;

	// --- Visual Improvements (Zero/Sub-0.05ms Overhead, Toggleable) ---
	public boolean visualSmoothFog = true;
	public boolean visualGradientDithering = true;
	public float visualDitherStrength = 1.0f;
	public boolean visualEnhancedAmbientOcclusion = true;
	public float visualAoStrength = 1.08f;
	public boolean visualSmoothBiomeBlending = true;
	public boolean visualSmoothChunkFade = true;
	public float visualChunkFadeDurationSeconds = 0.65f;
	public boolean visualDynamicLights = true;
	public int visualDynamicLightsUpdateIntervalMs = 16;
	public boolean visualBetterLeavesShading = true;
	public boolean visualBetterGrass = false;
	public boolean visualCrispMipmaps = true;
	public int visualAnisotropicLevel = 4; // 0, 2, 4, 8, 16
	public boolean visualAtmosphericHorizon = true;
	public boolean visualUnderwaterClarity = false;
	public boolean visualCleanGlassBorders = false;

	// --- Diagnostics ---
	public boolean diagnosticsHudOverlay = false;
	public boolean modStandDownEnabled = true;

	public enum LeavesCullingMode {
		OFF,
		CHECK_SURROUNDING,
		DEPTH,
		FAST_OPAQUE
	}
}
