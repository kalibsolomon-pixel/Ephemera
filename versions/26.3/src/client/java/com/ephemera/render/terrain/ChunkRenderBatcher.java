package com.ephemera.render.terrain;

import com.ephemera.core.memory.MemoryPressurePolicy;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.core.visuals.ChunkFadeMath;
import com.ephemera.mixin.client.terrain.TerrainFogRendererAccessor;
import com.ephemera.mixin.client.terrain.TerrainRenderSectionFadeAccessor;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.render.terrain.vulkan.VulkanMeshSectionHandle;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
import com.ephemera.visuals.VisualsRuntime;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * 26.3 allocation-light replacement for {@code LevelRenderer.prepareChunkRendersIndirect}.
 *
 * <p>Combines:
 * <ul>
 *   <li>Direct {@link SectionMeshRenderCache} + precomputed {@link SectionDrawState} reads (zero
 *   per-section {@code HashMap}/{@code EnumMap} lookups or heap offset divisions);</li>
 *   <li>Region-relative vertex baking so settled sections in the same 16x8x16-section region share
 *   a single 16-byte {@code ChunkSectionInfo} entry in {@link InstanceBufferRing};</li>
 *   <li>26.3 native {@code MULTIDRAW_TERRAIN} indirect batching ({@link RegionalChunkBatchPlan}),
 *   plus zero-upload steady-state frame-plan reuse;</li>
 *   <li>Vulkan {@code VK_EXT_mesh_shader} single-pass Task+Mesh rendering for SOLID &amp; CUTOUT;</li>
 *   <li>Fog occlusion, directional face-bucket culling, and visible-only animated sprite stamping.</li>
 * </ul>
 *
 * <p>When disabled or on unsupported hardware, returns {@code null} so vanilla's
 * {@code prepareChunkRendersIndirect} runs (with {@code TerrainLevelRendererChunkBatchMixin}'s
 * baked-origin fix active). Render thread only.
 */
public final class ChunkRenderBatcher {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final int LAYER_COUNT = LAYERS.length;
	private static final int TRANSLUCENT = ChunkSectionLayer.TRANSLUCENT.ordinal();
	private static final double FOG_PADDING = 1.0;
	private static final double CULL_MARGIN = 0.5;
	private static final long SPRITE_RESTAMP_MS = 50L;
	private static final boolean FADE_ACCESSOR =
		TerrainRenderSectionFadeAccessor.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);
	private static final boolean SWAP_HOOK =
		SectionMeshSwapHook.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);
	private static final boolean DIAG = Boolean.getBoolean("ephemera.diag.terrain");
	private static final EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> EMPTY_DRAW_GROUPS =
		createEmptyDrawGroups();
	private static final ChunkRenderBatcher INSTANCE = new ChunkRenderBatcher();

	private static EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> createEmptyDrawGroups() {
		EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> map = new EnumMap<>(ChunkSectionLayer.class);
		for (ChunkSectionLayer layer : LAYERS) {
			map.put(layer, List.of());
		}
		return map;
	}

	private final Matrix4f frameModelView = new Matrix4f();
	private final RegionalChunkBatchPlan plan = new RegionalChunkBatchPlan();
	private final InstanceBufferRing instanceRing = new InstanceBufferRing();
	// Flat L1-resident region-key -> instance-index table with O(1) 32-slot direct-mapped fast probe.
	private final long[] regionProbeKeys = new long[32];
	private final int[] regionProbeInsts = new int[32];
	private final int[] regionProbeGens = new int[32];
	private int regionGen = 1;
	private long[] regionKeys = new long[32];
	private int[] regionInsts = new int[32];
	private int regionCount;
	private final ObjectArrayList<SectionMeshRenderCache> spriteSections = new ObjectArrayList<>();

	// Flat per-instance ChunkSectionInfo arrays packed into InstanceBufferRing (zero GC).
	private int[] instX = new int[256];
	private int[] instY = new int[256];
	private int[] instZ = new int[256];
	private float[] instVis = new float[256];
	private int instCount;

	// Fading sections inside the instance buffer: only their visibility float updates during reuse.
	private SectionRenderDispatcher.RenderSection[] fadingSections = new SectionRenderDispatcher.RenderSection[32];
	private int[] fadingIndices = new int[32];
	private int fadingCount;

	// Vulkan VK_EXT_mesh_shader task lists & fading handles (zero GC across frames).
	private int[] meshSolidTasks = new int[1024];
	private int meshSolidTaskCount;
	private int[] meshCutoutTasks = new int[1024];
	private int meshCutoutTaskCount;
	private int meshSectionCount;
	private long meshTaskVersion;
	private int meshActiveLayerMask;
	private int[] meshVisUpdateSlots = new int[128];
	private float[] meshVisUpdateValues = new float[128];
	private SectionRenderDispatcher.RenderSection[] meshFadingSections = new SectionRenderDispatcher.RenderSection[64];
	private VulkanMeshSectionHandle[] meshFadingHandles = new VulkanMeshSectionHandle[64];
	private int meshFadingCount;

	private int lastSectionCount;
	private int lastFogCulled;
	private boolean lastRegional;
	private long reusedFrames;
	private long rebuiltFrames;
	private long lastDiagMs;
	private int lastUnbakedCount;

	// What the current regional plan was built from (frame-plan reuse).
	private boolean planValid;
	private @Nullable SectionRenderDispatcher planDispatcher;
	private int planEpoch;
	private long planTopology;
	private Object[] planSections = new Object[0];
	private int planSectionCount;
	private boolean planHasCamera;
	private double planCamX;
	private double planCamY;
	private double planCamZ;
	private boolean planFogCull;
	private float planEnvEnd;
	private float planRdEnd;
	private int planCullMask;
	private int planAtlasWidth;
	private int planAtlasHeight;
	private boolean planTrackSprites;
	private boolean planMeshActive;
	private boolean planSortTranslucent;
	private long planFadeMs;
	private boolean planSmoothFade;
	private long lastSpriteStamp = Long.MIN_VALUE / 4;

	// Per-frame camera, fog, and fade state.
	private boolean fogCull;
	private float envEnd;
	private float rdEnd;
	private boolean hasCamera;
	private double camX;
	private double camY;
	private double camZ;
	private int camBlockX;
	private int camBlockY;
	private int camBlockZ;
	private float camOffX;
	private float camOffY;
	private float camOffZ;
	private @Nullable Matrix4fc camProjection;
	private int atlasWidth;
	private int atlasHeight;
	private boolean frameSmoothFade;
	private long frameVanillaFadeMs;
	private long frameEffectiveFadeMs;

	private ChunkRenderBatcher() {
	}

	public static ChunkRenderBatcher get() {
		return INSTANCE;
	}

	/** Instance entries written last frame (shared regions plus fading/unbaked sections). */
	public int lastSectionCount() {
		return lastSectionCount;
	}

	/** Sections skipped by fog occlusion last frame. */
	public int lastFogCulled() {
		return lastFogCulled;
	}

	/** Frames that reused an unchanged regional plan instead of rebuilding it (cumulative). */
	public long reusedFrames() {
		return reusedFrames;
	}

	/** True when last frame used regional multi-draw. */
	public boolean lastFrameRegional() {
		return lastRegional;
	}

	/** Indirect commands and batches of the last regional frame. */
	public RegionalChunkBatchPlan plan() {
		return plan;
	}

	/** The regional plan built for {@code target}, or null when it was built some other way. */
	public static @Nullable RegionalChunkBatchPlan planFor(ChunkSectionsToRender target) {
		RegionalChunkBatchPlan p = INSTANCE.plan;
		return p.isFor(target) ? p : null;
	}

	/** Drops frame state and GPU command/instance buffers; called on dispatcher disposal / world unload. */
	public void release() {
		plan.release();
		instanceRing.close();
		VulkanMeshTerrainEngine.get().resetSections();
		planValid = false;
		planDispatcher = null;
		java.util.Arrays.fill(planSections, null);
		if (planSections.length > 4096) {
			planSections = new Object[0];
		}
		planSectionCount = 0;
		clearFading();
		clearMeshFading();
		if (fadingSections.length > 4096) {
			fadingSections = new SectionRenderDispatcher.RenderSection[32];
			fadingIndices = new int[32];
		}
		if (instX.length > 16384) {
			instX = new int[256];
			instY = new int[256];
			instZ = new int[256];
			instVis = new float[256];
		}
		instCount = 0;
		meshSolidTaskCount = 0;
		meshCutoutTaskCount = 0;
		meshSectionCount = 0;
		meshActiveLayerMask = 0;
		if (meshSolidTasks.length > 16384) {
			meshSolidTasks = new int[1024];
		}
		if (meshCutoutTasks.length > 16384) {
			meshCutoutTasks = new int[1024];
		}
		spriteSections.clear();
		spriteSections.trim(256);
		regionCount = 0;
		if (regionKeys.length > 256) {
			regionKeys = new long[32];
			regionInsts = new int[32];
		}
		TerrainTopology.bumpTopology();
	}

	/**
	 * Builds the frame's regional multi-draw chunk plan, or returns null to let vanilla's
	 * {@code prepareChunkRendersIndirect} run.
	 */
	public static @Nullable ChunkSectionsToRender tryPrepare(@Nullable SectionRenderDispatcher dispatcher,
															 ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
															 Matrix4fc modelViewMatrix, boolean sortTranslucent,
															 TextureManager textureManager,
															 LevelRenderState levelRenderState,
															 OptionsRenderState optionsRenderState) {
		INSTANCE.plan.bind(null);
		if (dispatcher == null || !TerrainFeatures.multiDraw()) {
			return null;
		}
		int epoch = ((SectionDispatcherEpoch) dispatcher).ephemera$epoch();
		if (epoch <= 0) {
			return null;
		}
		try {
			return INSTANCE.prepare(dispatcher, epoch, visibleSections, modelViewMatrix, sortTranslucent,
				textureManager, levelRenderState, optionsRenderState);
		} catch (Throwable t) {
			TerrainFeatures.disableMultiDraw(t);
			INSTANCE.planValid = false;
			INSTANCE.plan.begin(sortTranslucent);
			return null;
		}
	}

	private ChunkSectionsToRender prepare(SectionRenderDispatcher dispatcher, int epoch,
										  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
										  Matrix4fc modelViewMatrix, boolean sortTranslucent,
										  TextureManager textureManager, LevelRenderState levelRenderState,
										  OptionsRenderState optionsRenderState) {
		long now = Util.getMillis();
		VisibleSpriteTracker.beginFrame(now);

		GpuTextureView blockAtlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		atlasWidth = blockAtlas.getWidth(0);
		atlasHeight = blockAtlas.getHeight(0);
		frameModelView.set(modelViewMatrix);
		setupCamera(levelRenderState.cameraRenderState);
		setupFade(optionsRenderState);

		boolean cull = hasCamera && TerrainFeatures.directionalCulling();
		boolean wireframe = levelRenderState.renderWireframeTerrain;
		int cullMask = 0;
		for (int l = 0; l < LAYER_COUNT; l++) {
			RenderPipeline pipeline = LAYERS[l].pipeline(true);
			boolean layerCull = cull && l != TRANSLUCENT && (wireframe ? RenderPipelines.WIREFRAME_MULTIDRAW : pipeline).isCull();
			if (layerCull) {
				cullMask |= 1 << l;
			}
		}

		boolean trackSprites = VisibleSpriteTracker.trackingFrame();
		boolean meshActive = !wireframe && VulkanMeshTerrainEngine.get().beginFrame();
		if (canReusePlan(dispatcher, epoch, visibleSections, trackSprites, cullMask, meshActive, sortTranslucent)) {
			ChunkSectionsToRender reused = reuseRegional(trackSprites, now, meshActive, cull);
			if (reused != null) {
				lastRegional = true;
				return reused;
			}
		}
		ChunkSectionsToRender regional = prepareRegional(dispatcher, epoch, visibleSections, now,
			trackSprites, cullMask, meshActive, cull, sortTranslucent);
		lastRegional = true;
		return regional;
	}

	private void setupFade(@Nullable OptionsRenderState options) {
		frameSmoothFade = VisualsRuntime.smoothChunkFade();
		long vanillaMs = options != null ? Util.toMillis(options.chunkSectionFadeInTime) : 0L;
		frameVanillaFadeMs = vanillaMs;
		long effective = vanillaMs;
		if (vanillaMs > 0L && frameSmoothFade) {
			long configured = ChunkFadeMath.fadeDurationMs(VisualsRuntime.cfg().visualChunkFadeDurationSeconds);
			if (configured > 0L) {
				effective = configured;
			}
		}
		frameEffectiveFadeMs = effective;
	}

	private void setupCamera(@Nullable CameraRenderState camera) {
		fogCull = false;
		envEnd = Float.MAX_VALUE;
		rdEnd = Float.MAX_VALUE;
		hasCamera = false;
		camProjection = null;
		Vec3 pos = camera != null ? camera.pos : null;
		if (pos == null) {
			return;
		}
		hasCamera = true;
		camX = pos.x;
		camY = pos.y;
		camZ = pos.z;
		BlockPos bp = camera.blockPos;
		camBlockX = bp != null ? bp.getX() : (int) Math.floor(camX);
		camBlockY = bp != null ? bp.getY() : (int) Math.floor(camY);
		camBlockZ = bp != null ? bp.getZ() : (int) Math.floor(camZ);
		camOffX = (float) (camBlockX - camX);
		camOffY = (float) (camBlockY - camY);
		camOffZ = (float) (camBlockZ - camZ);
		camProjection = camera.projectionMatrix;
		if (TerrainFeatures.fogOcclusion() && TerrainFogRendererAccessor.getEphemeraFogEnabled()) {
			FogData fog = camera.fogData;
			if (fog != null && fog.color.w() >= 1.0f
				&& (fog.environmentalEnd < Float.MAX_VALUE || fog.renderDistanceEnd < Float.MAX_VALUE)) {
				fogCull = true;
				envEnd = fog.environmentalEnd;
				rdEnd = fog.renderDistanceEnd;
			}
		}
	}

	private float visibility(SectionRenderDispatcher.RenderSection section, long now) {
		long effectiveMs = frameEffectiveFadeMs;
		if (effectiveMs <= 0L) {
			return 1.0f;
		}
		if (SWAP_HOOK) {
			return ((SectionMeshSwapHook) section).ephemera$visibility(now, effectiveMs, frameSmoothFade);
		}
		if (FADE_ACCESSOR) {
			long uploaded = ((TerrainRenderSectionFadeAccessor) section).getEphemeraUploadedTime();
			if (now - uploaded >= effectiveMs) {
				return 1.0f;
			}
		}
		return section.getVisibility(now, frameVanillaFadeMs);
	}

	private boolean canReusePlan(SectionRenderDispatcher dispatcher, int epoch,
								 ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
								 boolean trackSprites, int cullMask, boolean meshActive,
								 boolean sortTranslucent) {
		if (!planValid || !TerrainFeatures.planReuse()
			|| dispatcher != planDispatcher || epoch != planEpoch
			|| planTopology != TerrainTopology.topology()
			|| hasCamera != planHasCamera || camX != planCamX || camY != planCamY || camZ != planCamZ
			|| fogCull != planFogCull || Float.compare(envEnd, planEnvEnd) != 0 || Float.compare(rdEnd, planRdEnd) != 0
			|| cullMask != planCullMask || atlasWidth != planAtlasWidth || atlasHeight != planAtlasHeight
			|| trackSprites != planTrackSprites || meshActive != planMeshActive
			|| sortTranslucent != planSortTranslucent
			|| frameEffectiveFadeMs != planFadeMs || frameSmoothFade != planSmoothFade
			|| !plan.reusable()) {
			return false;
		}
		int size = visibleSections.size();
		if (size != planSectionCount) {
			return false;
		}
		Object[] elements = visibleSections.elements();
		Object[] last = planSections;
		for (int i = 0; i < size; i++) {
			if (elements[i] != last[i]) {
				return false;
			}
		}
		return true;
	}

	/** Re-arms last frame's plan with zero indirect upload and (when settled) zero instance upload. */
	private @Nullable ChunkSectionsToRender reuseRegional(boolean trackSprites, long now,
														  boolean meshActive, boolean dirCull) {
		boolean anySettled = false;
		boolean instDirty = false;
		for (int i = 0; i < fadingCount; i++) {
			float v = visibility(fadingSections[i], now);
			int idx = fadingIndices[i];
			if (v != instVis[idx]) {
				instVis[idx] = v;
				instDirty = true;
			}
			if (v == 1.0f) {
				anySettled = true;
			}
		}
		if (anySettled) {
			planValid = false;
		}
		if (meshActive && (meshSolidTaskCount + meshCutoutTaskCount) > 0) {
			int visUpdateCount = 0;
			int i = 0;
			while (i < meshFadingCount) {
				float vis = visibility(meshFadingSections[i], now);
				VulkanMeshSectionHandle h = meshFadingHandles[i];
				if (vis != h.lastVisibility) {
					visUpdateCount = addMeshVisUpdate(visUpdateCount, h.slot, vis);
					h.lastVisibility = vis;
				}
				if (vis == 1.0f) {
					int last = --meshFadingCount;
					meshFadingSections[i] = meshFadingSections[last];
					meshFadingHandles[i] = meshFadingHandles[last];
					meshFadingSections[last] = null;
					meshFadingHandles[last] = null;
				} else {
					i++;
				}
			}
			VulkanMeshTerrainEngine.get().prepareFrame(
				meshSolidTasks, meshSolidTaskCount,
				meshCutoutTasks, meshCutoutTaskCount,
				meshTaskVersion, meshActiveLayerMask,
				meshVisUpdateSlots, meshVisUpdateValues, visUpdateCount,
				frameModelView, camProjection, camBlockX, camBlockY, camBlockZ,
				camOffX, camOffY, camOffZ, atlasWidth, atlasHeight,
				fogCull, envEnd, rdEnd, dirCull);
		}

		GpuBufferSlice terrainTransformUbo = RenderSystem.getDynamicUniforms()
			.writeTerrainTransform(frameModelView, atlasWidth, atlasHeight);
		GpuBufferSlice updatedInst = instDirty && instCount > 0
			? instanceRing.upload(instX, instY, instZ, instVis, instCount)
			: null;
		if (!plan.reuse(terrainTransformUbo, updatedInst)) {
			return null;
		}
		int maxIndices = plan.maxIndicesRequired();
		if (maxIndices > 0) {
			RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(maxIndices);
		}
		if (trackSprites && now - lastSpriteStamp >= SPRITE_RESTAMP_MS) {
			Object[] caches = spriteSections.elements();
			for (int i = 0, n = spriteSections.size(); i < n; i++) {
				SectionMeshRenderCache c = (SectionMeshRenderCache) caches[i];
				VisibleSpriteTracker.markSection(c, now);
				c.ephemera$setLastSpriteStampMs(now);
			}
			lastSpriteStamp = now;
		}
		reusedFrames++;
		ChunkSectionsToRender result = new ChunkSectionsToRender.DrawIndirect(
			terrainTransformUbo, EMPTY_DRAW_GROUPS, maxIndices, updatedInst);
		plan.bind(result);
		return result;
	}

	private ChunkSectionsToRender prepareRegional(SectionRenderDispatcher dispatcher, int epoch,
												  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
												  long now, boolean trackSprites, int cullMask,
												  boolean meshActive, boolean dirCull, boolean sortTranslucent) {
		long topology = TerrainTopology.topology();
		planValid = false;
		clearFading();
		clearMeshFading();
		meshSolidTaskCount = 0;
		meshCutoutTaskCount = 0;
		meshSectionCount = 0;
		meshActiveLayerMask = 0;
		int meshVisUpdates = 0;
		VulkanMeshTerrainEngine meshEngine = meshActive ? VulkanMeshTerrainEngine.get() : null;
		boolean meshTaskMode = meshEngine != null && meshEngine.usesTaskShader();
		int meshEpoch = meshEngine != null ? meshEngine.arenaEpoch() : -1;

		instCount = 0;
		plan.begin(sortTranslucent);
		regionCount = 0;
		if (++regionGen == 0) {
			java.util.Arrays.fill(regionProbeGens, 0);
			regionGen = 1;
		}
		spriteSections.clear();
		long lastRegionKey = Long.MIN_VALUE;
		int lastRegionInst = -1;

		boolean doFog = fogCull;
		double cx = camX;
		double cy = camY;
		double cz = camZ;
		double cxMin = cx + FOG_PADDING;
		double cyMin = cy + FOG_PADDING;
		double czMin = cz + FOG_PADDING;
		double cxMax = cx - (16.0 + FOG_PADDING);
		double cyMax = cy - (16.0 + FOG_PADDING);
		double czMax = cz - (16.0 + FOG_PADDING);
		boolean checkEnvFog = doFog && envEnd < Float.MAX_VALUE;
		double envSq = checkEnvFog ? (envEnd <= 0.0f ? 0.0 : (double) envEnd * (double) envEnd) : Double.POSITIVE_INFINITY;
		boolean checkRdFog = doFog && rdEnd < Float.MAX_VALUE;
		double rdVal = rdEnd;
		double rdSq = checkRdFog ? (rdVal <= 0.0 ? 0.0 : rdVal * rdVal) : Double.POSITIVE_INFINITY;
		int fogCulled = 0;
		int unbakedCount = 0;
		Object[] elements = visibleSections.elements();
		int size = visibleSections.size();
		if (meshEngine != null) {
			int minCap = Math.max(1024, size + 64);
			if (meshSolidTasks.length < minCap) {
				meshSolidTasks = new int[Integer.highestOneBit(minCap - 1) << 1];
			}
			if (meshCutoutTasks.length < minCap) {
				meshCutoutTasks = new int[Integer.highestOneBit(minCap - 1) << 1];
			}
		}
		int[] solidTasks = meshSolidTasks;
		int solidCount = 0;
		int[] cutoutTasks = meshCutoutTasks;
		int cutoutCount = 0;
		int meshSections = 0;
		int activeMeshMask = 0;
		int meshUploadedThisFrame = 0;
		long meshUploadElapsedNs = 0L;
		long meshUploadBudgetNs = meshEngine != null ? EphemeraPacerRuntime.currentUploadBudgetNs() : 0L;
		FrameBudgetPacer budgetPacer = EphemeraPacerRuntime.budget();
		boolean deferredMeshUpload = false;
		int spriteGeneration = trackSprites ? VisibleSpriteTracker.generation().id : 0;
		boolean useSwapHook = SWAP_HOOK;
		long effectiveFadeMs = frameEffectiveFadeMs;
		boolean fadeEnabled = effectiveFadeMs > 0L;
		boolean smoothFade = frameSmoothFade;
		dispatcher.lock();
		try {
			for (int i = 0; i < size; i++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
				SectionMeshRenderCache cache = useSwapHook
					? ((SectionMeshSwapHook) section).ephemera$compiledCache()
					: (section.getSectionMesh() instanceof SectionMeshRenderCache c ? c : null);
				if (cache == null) {
					continue;
				}
				int mask = cache.ephemera$readyMask(epoch);
				if (mask == 0) {
					continue;
				}
				BlockPos origin = null;
				VulkanMeshSectionHandle existingMesh = meshEngine != null ? cache.ephemera$meshHandle() : null;
				boolean meshReady = existingMesh != null && !existingMesh.released && existingMesh.arenaEpoch == meshEpoch;
				if (doFog) {
					int ox, oy, oz;
					if (meshReady) {
						ox = existingMesh.sectionX;
						oy = existingMesh.sectionY;
						oz = existingMesh.sectionZ;
					} else if (cache.ephemera$hasSectionCoords()) {
						ox = cache.ephemera$sectionX();
						oy = cache.ephemera$sectionY();
						oz = cache.ephemera$sectionZ();
					} else {
						origin = section.getRenderOrigin();
						ox = origin.getX();
						oy = origin.getY();
						oz = origin.getZ();
					}
					double fMinX = ox - cxMin, fMaxX = ox - cxMax;
					double dx = fMinX > 0.0 ? fMinX : (fMaxX < 0.0 ? -fMaxX : 0.0);
					double fMinZ = oz - czMin, fMaxZ = oz - czMax;
					double dz = fMinZ > 0.0 ? fMinZ : (fMaxZ < 0.0 ? -fMaxZ : 0.0);
					double horizSq = dx * dx + dz * dz;
					if (checkRdFog && horizSq >= rdSq) {
						fogCulled++;
						continue;
					}
					double fMinY = oy - cyMin, fMaxY = oy - cyMax;
					double dy = fMinY > 0.0 ? fMinY : (fMaxY < 0.0 ? -fMaxY : 0.0);
					if ((checkRdFog && dy >= rdVal) || (checkEnvFog && horizSq + dy * dy >= envSq)) {
						fogCulled++;
						continue;
					}
				}

				float visibility = fadeEnabled
					? (useSwapHook
						? ((SectionMeshSwapHook) section).ephemera$visibility(now, effectiveFadeMs, smoothFade)
						: visibility(section, now))
					: 1.0f;
				SectionLayout layout = null;

				// Vulkan VK_EXT_mesh_shader fast path for SOLID & CUTOUT.
				if (meshEngine != null) {
					VulkanMeshSectionHandle h = meshReady ? existingMesh : null;
					if (h == null) {
						layout = cache.ephemera$layout();
						if (layout != null && layout.meshlets != null) {
							if (budgetPacer.allowSectionUpload(meshUploadedThisFrame, meshUploadElapsedNs, meshUploadBudgetNs)) {
								long tUp = System.nanoTime();
								h = meshEngine.uploadSection(layout.meshlets, visibility);
								meshUploadElapsedNs += Math.max(0L, System.nanoTime() - tUp);
								cache.ephemera$setMeshHandle(h);
								if (h != null) {
									meshUploadedThisFrame++;
									long freed = layout.releaseCpuMeshlets();
									if (freed > 0L) {
										MemoryPressurePolicy.recordHeapReclaimed(freed);
									}
								}
							} else {
								h = null;
								deferredMeshUpload = true;
							}
						} else {
							h = null;
						}
					}
					if (h != null) {
						int handledLayers = h.layerMask & mask;
						if (handledLayers != 0) {
							meshSections++;
							if (!meshTaskMode) {
								if (solidCount == solidTasks.length) {
									solidTasks = meshSolidTasks = java.util.Arrays.copyOf(solidTasks, solidTasks.length * 2);
								}
								solidTasks[solidCount++] = h.slot;
							} else {
								int prefix = h.slotPrefix;
								if ((handledLayers & 1) != 0) {
									int chunks = h.solidTaskChunks;
									if (chunks == 1) {
										if (solidCount == solidTasks.length) {
											solidTasks = meshSolidTasks = java.util.Arrays.copyOf(solidTasks, solidTasks.length * 2);
										}
										solidTasks[solidCount++] = prefix;
									} else if (chunks > 1) {
										if (solidCount + chunks > solidTasks.length) {
											solidTasks = meshSolidTasks = java.util.Arrays.copyOf(solidTasks, Math.max(solidTasks.length * 2, solidCount + chunks));
										}
										for (int c = 0; c < chunks; c++) {
											solidTasks[solidCount++] = prefix | c;
										}
									}
								}
								if ((handledLayers & 2) != 0) {
									int chunks = h.cutoutTaskChunks;
									if (chunks == 1) {
										if (cutoutCount == cutoutTasks.length) {
											cutoutTasks = meshCutoutTasks = java.util.Arrays.copyOf(cutoutTasks, cutoutTasks.length * 2);
										}
										cutoutTasks[cutoutCount++] = prefix;
									} else if (chunks > 1) {
										if (cutoutCount + chunks > cutoutTasks.length) {
											cutoutTasks = meshCutoutTasks = java.util.Arrays.copyOf(cutoutTasks, Math.max(cutoutTasks.length * 2, cutoutCount + chunks));
										}
										for (int c = 0; c < chunks; c++) {
											cutoutTasks[cutoutCount++] = prefix | c;
										}
									}
								}
							}
							activeMeshMask |= handledLayers;
							if (visibility != h.lastVisibility) {
								meshVisUpdates = addMeshVisUpdate(meshVisUpdates, h.slot, visibility);
								h.lastVisibility = visibility;
							}
							if (visibility < 1.0f) {
								trackMeshFading(section, h);
							}
							mask &= ~handledLayers;
						}
					}
				}

				if (mask != 0) {
					SectionDrawState state = cache.ephemera$drawState(epoch);
					if (layout == null) {
						layout = cache.ephemera$layout();
					}
					boolean regionShared = layout != null && layout.regionRelative && visibility == 1.0f;
					if (!regionShared && visibility == 1.0f) {
						unbakedCount++;
					}

					int instIdx;
					if (regionShared) {
						long key = layout.regionKey;
						if (key == lastRegionKey) {
							instIdx = lastRegionInst;
						} else {
							instIdx = findOrAddRegionInstance(key, layout.originX, layout.originY, layout.originZ);
							lastRegionKey = key;
							lastRegionInst = instIdx;
						}
					} else {
						if (origin == null && layout == null) {
							origin = section.getRenderOrigin();
						}
						instIdx = layout != null
							? addInstance(layout.originX, layout.originY, layout.originZ, visibility)
							: addInstance(origin.getX(), origin.getY(), origin.getZ(), visibility);
						if (visibility != 1.0f) {
							trackFading(section, instIdx);
						}
					}

					if ((mask & cullMask) != 0 && (cx != state.lastCamX || cy != state.lastCamY || cz != state.lastCamZ)) {
						state.lastCamX = cx;
						state.lastCamY = cy;
						state.lastCamZ = cz;
						state.validCamLayers = 0;
					}

					int custom = state.customIndexMask;
					do {
						int l = Integer.numberOfTrailingZeros(mask);
						int bit = 1 << l;
						mask &= mask - 1;
						GpuBuffer vertexBuffer = state.vertexBuffers[l];
						int baseVertex = state.baseVertices[l];
						if ((custom & bit) != 0) {
							plan.addIndexed(l, vertexBuffer, state.indexBuffers[l], state.indexTypes[l],
								state.firstIndices[l], state.indexCounts[l], baseVertex, instIdx);
							continue;
						}
						QuadBuckets buckets = (cullMask & bit) != 0 ? state.cullBuckets[l] : null;
						if (buckets != null) {
							int spanOff = l * SectionDrawState.SPAN_STRIDE;
							int n;
							if ((state.validCamLayers & bit) != 0) {
								n = state.cachedSpanCount[l];
							} else {
								int vm = buckets.visibleMask(cx - layout.sectionX, cy - layout.sectionY, cz - layout.sectionZ, CULL_MARGIN);
								if (vm != state.cachedCullMask[l]) {
									state.cachedCullMask[l] = vm;
									state.cachedSpanCount[l] = buckets.spans(vm, state.cachedSpans, spanOff);
								}
								state.validCamLayers |= bit;
								n = state.cachedSpanCount[l];
							}
							if (n != 0) {
								plan.addQuadSpans(l, vertexBuffer, baseVertex, state.cachedSpans, spanOff, n, instIdx);
							}
						} else {
							plan.addSequential(l, vertexBuffer, baseVertex, state.indexCounts[l], instIdx);
						}
					} while (mask != 0);
				}

				if (trackSprites && cache.ephemera$needsSpriteStamp(spriteGeneration)) {
					if (now - cache.ephemera$lastSpriteStampMs() >= SPRITE_RESTAMP_MS) {
						VisibleSpriteTracker.markSection(cache, now);
						cache.ephemera$setLastSpriteStampMs(now);
					}
					spriteSections.add(cache);
				}
			}
		} finally {
			dispatcher.unlock();
		}

		meshSolidTaskCount = solidCount;
		meshCutoutTaskCount = cutoutCount;
		meshSectionCount = meshSections;
		meshActiveLayerMask = activeMeshMask;
		if (trackSprites) {
			lastSpriteStamp = now;
		}
		if (meshEngine != null && (meshSolidTaskCount + meshCutoutTaskCount) > 0) {
			meshTaskVersion++;
			meshEngine.prepareFrame(
				meshSolidTasks, meshSolidTaskCount,
				meshCutoutTasks, meshCutoutTaskCount,
				meshTaskVersion, meshActiveLayerMask,
				meshVisUpdateSlots, meshVisUpdateValues, meshVisUpdates,
				frameModelView, camProjection, camBlockX, camBlockY, camBlockZ,
				camOffX, camOffY, camOffZ, atlasWidth, atlasHeight,
				fogCull, envEnd, rdEnd, dirCull);
		}

		lastSectionCount = instCount;
		lastFogCulled = fogCulled;
		lastUnbakedCount = unbakedCount;
		rebuiltFrames++;

		GpuBufferSlice terrainTransformUbo = RenderSystem.getDynamicUniforms()
			.writeTerrainTransform(frameModelView, atlasWidth, atlasHeight);
		GpuBufferSlice instSlice = instCount > 0
			? instanceRing.upload(instX, instY, instZ, instVis, instCount)
			: null;
		plan.finish(terrainTransformUbo, instSlice);
		int maxIndices = plan.maxIndicesRequired();
		if (maxIndices > 0) {
			RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(maxIndices);
		}

		if (DIAG && now - lastDiagMs >= 2000L) {
			lastDiagMs = now;
			TerrainFeatures.LOGGER.info("TerrainDiag(26.3): visible={} fogCulled={} meshSec={} meshTasks={}/{} instances={} batches={} cmds={} fading={} unbaked={} sortTrans={} reused={} rebuilt={}",
				size, fogCulled, meshSectionCount, meshSolidTaskCount, meshCutoutTaskCount, instCount, plan.lastBatchCount(), plan.lastCommandCount(), fadingCount, unbakedCount, sortTranslucent, reusedFrames, rebuiltFrames);
		}

		ChunkSectionsToRender result = new ChunkSectionsToRender.DrawIndirect(
			terrainTransformUbo, EMPTY_DRAW_GROUPS, maxIndices, instSlice);
		plan.bind(result);

		if (planSections.length < size) {
			planSections = new Object[Math.max(size, planSections.length + (planSections.length >> 1))];
		}
		System.arraycopy(elements, 0, planSections, 0, size);
		if (planSectionCount > size) {
			java.util.Arrays.fill(planSections, size, planSectionCount, null);
		}
		planSectionCount = size;
		planDispatcher = dispatcher;
		planEpoch = epoch;
		planTopology = topology;
		planHasCamera = hasCamera;
		planCamX = camX;
		planCamY = camY;
		planCamZ = camZ;
		planFogCull = fogCull;
		planEnvEnd = envEnd;
		planRdEnd = rdEnd;
		planCullMask = cullMask;
		planAtlasWidth = atlasWidth;
		planAtlasHeight = atlasHeight;
		planTrackSprites = trackSprites;
		planMeshActive = meshActive;
		planSortTranslucent = sortTranslucent;
		planFadeMs = frameEffectiveFadeMs;
		planSmoothFade = frameSmoothFade;
		planValid = !deferredMeshUpload;
		return result;
	}

	private int findOrAddRegionInstance(long key, int originX, int originY, int originZ) {
		int gen = regionGen;
		int slot = (int) (key ^ (key >>> 17) ^ (key >>> 34)) & 31;
		if (regionProbeGens[slot] == gen && regionProbeKeys[slot] == key) {
			return regionProbeInsts[slot];
		}
		long[] keys = regionKeys;
		int count = regionCount;
		for (int i = 0; i < count; i++) {
			if (keys[i] == key) {
				int instIdx = regionInsts[i];
				regionProbeKeys[slot] = key;
				regionProbeInsts[slot] = instIdx;
				regionProbeGens[slot] = gen;
				return instIdx;
			}
		}
		int instIdx = addInstance(originX, originY, originZ, 1.0f);
		if (count == keys.length) {
			int next = count << 1;
			regionKeys = keys = java.util.Arrays.copyOf(keys, next);
			regionInsts = java.util.Arrays.copyOf(regionInsts, next);
		}
		keys[count] = key;
		regionInsts[count] = instIdx;
		regionCount = count + 1;
		regionProbeKeys[slot] = key;
		regionProbeInsts[slot] = instIdx;
		regionProbeGens[slot] = gen;
		return instIdx;
	}

	private int addInstance(int x, int y, int z, float vis) {
		int idx = instCount;
		if (idx == instX.length) {
			int next = idx * 2;
			instX = java.util.Arrays.copyOf(instX, next);
			instY = java.util.Arrays.copyOf(instY, next);
			instZ = java.util.Arrays.copyOf(instZ, next);
			instVis = java.util.Arrays.copyOf(instVis, next);
		}
		instX[idx] = x;
		instY[idx] = y;
		instZ[idx] = z;
		instVis[idx] = vis;
		instCount = idx + 1;
		return idx;
	}

	private void clearFading() {
		if (fadingCount > 0) {
			java.util.Arrays.fill(fadingSections, 0, fadingCount, null);
			fadingCount = 0;
		}
	}

	private void trackFading(SectionRenderDispatcher.RenderSection section, int instIdx) {
		int n = fadingCount;
		if (n == fadingSections.length) {
			int next = n * 2;
			fadingSections = java.util.Arrays.copyOf(fadingSections, next);
			fadingIndices = java.util.Arrays.copyOf(fadingIndices, next);
		}
		fadingSections[n] = section;
		fadingIndices[n] = instIdx;
		fadingCount = n + 1;
	}

	private void clearMeshFading() {
		if (meshFadingCount > 0) {
			java.util.Arrays.fill(meshFadingSections, 0, meshFadingCount, null);
			java.util.Arrays.fill(meshFadingHandles, 0, meshFadingCount, null);
			meshFadingCount = 0;
		}
	}

	private void trackMeshFading(SectionRenderDispatcher.RenderSection section, VulkanMeshSectionHandle handle) {
		int n = meshFadingCount;
		if (n == meshFadingSections.length) {
			int next = n * 2;
			meshFadingSections = java.util.Arrays.copyOf(meshFadingSections, next);
			meshFadingHandles = java.util.Arrays.copyOf(meshFadingHandles, next);
		}
		meshFadingSections[n] = section;
		meshFadingHandles[n] = handle;
		meshFadingCount = n + 1;
	}

	private int addMeshVisUpdate(int count, int slot, float vis) {
		if (count == meshVisUpdateSlots.length) {
			int next = count * 2;
			meshVisUpdateSlots = java.util.Arrays.copyOf(meshVisUpdateSlots, next);
			meshVisUpdateValues = java.util.Arrays.copyOf(meshVisUpdateValues, next);
		}
		meshVisUpdateSlots[count] = slot;
		meshVisUpdateValues[count] = vis;
		return count + 1;
	}
}
