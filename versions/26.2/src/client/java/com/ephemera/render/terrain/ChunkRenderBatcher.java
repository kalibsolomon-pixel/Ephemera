package com.ephemera.render.terrain;

import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.core.memory.MemoryPressurePolicy;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.core.terrain.FastFrustumMath;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.core.terrain.SectionVertexBaker;
import com.ephemera.mixin.client.terrain.TerrainFogRendererAccessor;
import com.ephemera.mixin.client.terrain.TerrainRenderSectionFadeAccessor;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.render.terrain.gl.GlSingleMdiTerrain;
import com.ephemera.render.terrain.vulkan.VulkanMeshSectionHandle;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
import com.ephemera.visuals.VisualsRuntime;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Allocation-light replacement for {@code LevelRenderer.prepareChunkRenders}, with two
 * execution modes.
 *
 * <p><b>Regional multi-draw</b> (default when the device has {@code multiDrawDirectSeparate},
 * or {@code drawIndirect} and {@code multiDrawIndirect}): sections whose vertices were baked
 * region-relative and have finished fading share one {@code ChunkSection} uniform per
 * 16x8x16-section region, and every (layer, uniform, heap) batch becomes one
 * {@code multiDrawIndexed} (OpenGL) or {@code drawIndexedIndirect} (Vulkan) via a
 * {@link RegionalChunkBatchPlan}. Fading or unbaked sections keep a uniform of their own.
 * The shader is unchanged: region-relative positions plus the region origin give the same
 * world position. Per-layer draw parameters come precomputed from {@link SectionDrawState}.
 * When nothing the plan was built from changed (see {@link TerrainTopology}; same camera
 * position, fog, culling and visible list, no fading section), last frame's plan is reused
 * and only the section uniforms are rewritten.
 *
 * <p><b>OpenGL single multi-draw</b> (see {@link GlSingleMdiTerrain}): on top of regional
 * multi-draw, solid and cutout draws share uniform slot 0 and select their region (or fading
 * section) origin and visibility through {@code firstInstance}, so each of those layers is one
 * multi-draw per vertex heap and fading sections need no uniform of their own. When the
 * translucent program has the batch block too, translucent draws do the same, so consecutive
 * ones on the same heaps merge into one back-to-front run.
 *
 * <p><b>Per-section fallback</b>: produces vanilla's {@link ChunkSectionsToRender} (same
 * grouping hash, per-layer order and UBO contents, except that baked meshes get their baked
 * origin) while reusing maps, lists, {@code RenderPass.Draw} records and section infos.
 *
 * <p>Both modes:
 * <ul>
 *   <li>read allocations, draws and section infos straight from {@link SectionMeshRenderCache}
 *   instead of vanilla's per-layer {@code HashMap} lookups;</li>
 *   <li>skip {@code getVisibility} for settled sections (same {@code now - uploadedTime >=
 *   fadeDuration} test vanilla uses to return 1);</li>
 *   <li>skip sections that the terrain shader would render entirely in fog colour;</li>
 *   <li>skip directional buckets of solid/cutout geometry facing away from the camera (the
 *   fallback trims each draw to the range covering the visible buckets).</li>
 * </ul>
 *
 * <p>Render thread only. The returned object is valid until the next call, which matches
 * vanilla's use within a single frame.
 */
public final class ChunkRenderBatcher {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final int LAYER_COUNT = LAYERS.length;
	private static final int TRANSLUCENT = ChunkSectionLayer.TRANSLUCENT.ordinal();
	/** Layers drawn through single multi-draw batch entries when their draw is sequentially indexed. */
	private static final int GL_MDI_LAYERS = (1 << ChunkSectionLayer.SOLID.ordinal()) | (1 << ChunkSectionLayer.CUTOUT.ordinal());
	/** The translucent layer, drawn through batch entries (sorted index buffer or not) when its program has the block. */
	private static final int GL_MDI_TRANSLUCENT = 1 << TRANSLUCENT;
	/** Groups are keyed by heap buffer identity; drop stale keys once heaps have churned. */
	private static final int MAX_GROUPS_BEFORE_RESET = 64;
	/** Block models may extend one block outside their cell. */
	private static final double FOG_PADDING = 1.0;
	/**
	 * Slack on measured bucket planes: covers eye offsets applied after the camera position
	 * (view bobbing translates the projection by up to ~0.1 block) and float rounding.
	 */
	private static final double CULL_MARGIN = 0.5;
	private static final GpuBufferSlice[] NO_SLICES = new GpuBufferSlice[0];
	/** Bound on cached region uniform records (regions ever seen); cleared when exceeded. */
	private static final int MAX_REGION_INFOS = 4096;
	/**
	 * How often a reused plan re-stamps its visible animated sprites; well inside
	 * {@code VisibleSpriteTracker}'s 200 ms grace window, so nothing visible ever freezes.
	 */
	private static final long SPRITE_RESTAMP_MS = 50L;
	private static final boolean FADE_ACCESSOR =
		TerrainRenderSectionFadeAccessor.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);
	private static final boolean SWAP_HOOK =
		SectionMeshSwapHook.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);
	private static final boolean DIAG = Boolean.getBoolean("ephemera.diag.terrain");
	private static final ChunkRenderBatcher INSTANCE = new ChunkRenderBatcher();

	private final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups =
		new EnumMap<>(ChunkSectionLayer.class);
	private final Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>[] groups;
	private final Matrix4f frameModelView = new Matrix4f();
	private final int[] vertexSizes = new int[LAYER_COUNT];
	private final boolean[] cullLayer = new boolean[LAYER_COUNT];
	private final RegionalChunkBatchPlan plan = new RegionalChunkBatchPlan();
	private final Long2IntOpenHashMap regionUbos = new Long2IntOpenHashMap();
	/** Region uniform records, reused across frames while the atlas size is unchanged. */
	private final Long2ObjectOpenHashMap<DynamicUniforms.ChunkSectionInfo> regionInfos = new Long2ObjectOpenHashMap<>();
	/** Drawn meshes whose sprites a reused plan must keep stamping. */
	private final ObjectArrayList<SectionMeshRenderCache> spriteSections = new ObjectArrayList<>();
	private DynamicUniforms.ChunkSectionInfo[] infos = new DynamicUniforms.ChunkSectionInfo[1024];
	private DynamicUniforms.ChunkSectionInfo[] exactInfos = new DynamicUniforms.ChunkSectionInfo[0];
	@SuppressWarnings("unchecked")
	private BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader>[] uploaders = new BiConsumer[1024];

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
	private long planDigest1;
	private long planDigest2;
	private int planDigestSize = -1;
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
	private boolean planDirect;
	private boolean planMeshActive;
	private boolean planCamIndependent;
	private boolean planGlSingleMdi;
	private boolean planGlSingleMdiTranslucent;
	private long lastSpriteStamp = Long.MIN_VALUE / 4;
	private long lastMeshScanTopology = Long.MIN_VALUE;
	private int lastMeshScanEpoch = Integer.MIN_VALUE;

	// Fading sections inside a reusable plan: only their visibility uniform changes frame-to-frame.
	private SectionRenderDispatcher.RenderSection[] fadingSections = new SectionRenderDispatcher.RenderSection[32];
	private SectionMeshRenderCache[] fadingCaches = new SectionMeshRenderCache[32];
	private @Nullable SectionLayout[] fadingLayouts = new SectionLayout[32];
	private @Nullable BlockPos[] fadingOrigins = new BlockPos[32];
	private int[] fadingUbos = new int[32];
	private int fadingCount;

	// OpenGL single multi-draw: batch entry per region (rebuilt with the plan) and fading entries.
	private final Long2IntOpenHashMap regionBatchEntries = new Long2IntOpenHashMap();
	private SectionRenderDispatcher.RenderSection[] glMdiFadingSections = new SectionRenderDispatcher.RenderSection[32];
	private int[] glMdiFadingEntries = new int[32];
	private float[] glMdiVisScratch = new float[32];
	private int glMdiFadingCount;
	/** Slot-0 uniform of single multi-draw frames (origin 0, visibility 1), reused while the atlas size holds. */
	private DynamicUniforms.@Nullable ChunkSectionInfo glSingleMdiFrameInfo;
	private int lastBatchEntries;

	// Decoupled, O(1) two-tier (nearby <= 48 blocks vs distant) pending Vulkan mesh upload queue
	// drained under the shared frame streaming budget without rebuilding the draw plan every frame.
	private static final int DISTANT_PROMOTE_BATCH = 32;
	private SectionMeshRenderCache[] nearPendingCaches = new SectionMeshRenderCache[32];
	private float[] nearPendingVis = new float[32];
	private int nearPendingCount;
	private SectionMeshRenderCache[] farPendingCaches = new SectionMeshRenderCache[64];
	private float[] farPendingVis = new float[64];
	private int farPendingCount;
	private int pendingPromotedCount;

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

	// Per-frame camera and fog state.
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
	private int uboCount;
	/** {@code VisualsRuntime.smoothChunkFade()}, read once per frame. */
	private boolean frameSmoothFade;

	@SuppressWarnings("unchecked")
	private ChunkRenderBatcher() {
		groups = new Int2ObjectOpenHashMap[LAYER_COUNT];
		for (ChunkSectionLayer layer : LAYERS) {
			Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> map = new Int2ObjectOpenHashMap<>();
			groups[layer.ordinal()] = map;
			drawGroups.put(layer, map);
		}
		regionUbos.defaultReturnValue(-1);
		regionBatchEntries.defaultReturnValue(-1);
	}

	public static ChunkRenderBatcher get() {
		return INSTANCE;
	}

	/** {@code ChunkSection} uniforms written last frame (sections in fallback mode, regions plus fading sections in regional mode). */
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

	/** Drops frame state and GPU command buffers; called when the section dispatcher is disposed. */
	public void release() {
		plan.release();
		GlSingleMdiTerrain.get().release();
		VulkanMeshTerrainEngine.get().resetSections();
		com.ephemera.core.terrain.MeshletBaker.clearPool();
		resetGroups();
		planValid = false;
		planCamIndependent = false;
		lastMeshScanTopology = Long.MIN_VALUE;
		lastMeshScanEpoch = Integer.MIN_VALUE;
		planDispatcher = null;
		java.util.Arrays.fill(planSections, null);
		if (planSections.length > 4096) {
			planSections = new Object[0];
		}
		planSectionCount = 0;
		clearFading();
		clearMeshFading();
		clearGlMdiFading();
		clearPendingMeshUploads(true);
		if (glMdiFadingSections.length > 4096) {
			glMdiFadingSections = new SectionRenderDispatcher.RenderSection[32];
			glMdiFadingEntries = new int[32];
			glMdiVisScratch = new float[32];
		}
		regionBatchEntries.clear();
		regionBatchEntries.trim(256);
		glSingleMdiFrameInfo = null;
		planGlSingleMdi = false;
		meshSolidTaskCount = 0;
		meshCutoutTaskCount = 0;
		meshSectionCount = 0;
		meshActiveLayerMask = 0;
		java.util.Arrays.fill(infos, null);
		exactInfos = new DynamicUniforms.ChunkSectionInfo[0];
		java.util.Arrays.fill(uploaders, null);
		if (infos.length > 4096) {
			infos = new DynamicUniforms.ChunkSectionInfo[1024];
			@SuppressWarnings("unchecked")
			BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader>[] freshUploaders = new BiConsumer[1024];
			uploaders = freshUploaders;
		}
		if (meshSolidTasks.length > 16384) {
			meshSolidTasks = new int[1024];
		}
		if (meshCutoutTasks.length > 16384) {
			meshCutoutTasks = new int[1024];
		}
		spriteSections.clear();
		spriteSections.trim(256);
		regionInfos.clear();
		regionInfos.trim(256);
		regionUbos.clear();
		regionUbos.trim(256);
		TerrainTopology.bumpTopology();
	}

	/**
	 * Builds the frame's chunk draws, or returns null to let vanilla run (feature disabled,
	 * required mixins missing, no dispatcher, or an unexpected failure).
	 */
	public static @Nullable ChunkSectionsToRender tryPrepare(@Nullable SectionRenderDispatcher dispatcher,
															 ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
															 Matrix4fc modelViewMatrix, TextureManager textureManager,
															 LevelRenderState levelRenderState) {
		INSTANCE.plan.bind(null);
		if (dispatcher == null || !TerrainFeatures.batcher()) {
			return null;
		}
		int epoch = ((SectionDispatcherEpoch) dispatcher).ephemera$epoch();
		if (epoch <= 0) {
			return null;
		}
		try {
			return INSTANCE.prepare(dispatcher, epoch, visibleSections, modelViewMatrix, textureManager, levelRenderState);
		} catch (Throwable t) {
			TerrainFeatures.disableBatcher(t);
			return null;
		}
	}

	private long lastNowMs;
	private long lastNowSyncNanos;

	private long frameNowMs() {
		long nanoNow = System.nanoTime();
		if (lastNowSyncNanos == 0L || (nanoNow - lastNowSyncNanos) >= 1_000_000L) {
			lastNowMs = Util.getMillis();
			lastNowSyncNanos = nanoNow;
		}
		return lastNowMs;
	}

	private ChunkSectionsToRender prepare(SectionRenderDispatcher dispatcher, int epoch,
										  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
										  Matrix4fc modelViewMatrix, TextureManager textureManager,
										  LevelRenderState levelRenderState) {
		long now = frameNowMs();
		VisibleSpriteTracker.beginFrame(now);

		GpuTextureView blockAtlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		atlasWidth = blockAtlas.getWidth(0);
		atlasHeight = blockAtlas.getHeight(0);
		frameModelView.set(modelViewMatrix);
		setupCamera(levelRenderState.cameraRenderState);
		frameSmoothFade = VisualsRuntime.smoothChunkFade();

		boolean cull = hasCamera && TerrainFeatures.directionalCulling();
		boolean wireframe = SharedConstants.DEBUG_HOTKEYS && Minecraft.getInstance().wireframe;
		int cullMask = 0;
		for (int l = 0; l < LAYER_COUNT; l++) {
			RenderPipeline pipeline = LAYERS[l].pipeline();
			vertexSizes[l] = pipeline.getVertexFormatBinding(0).getVertexSize();
			// Bucket culling only removes what back-face culling would discard anyway.
			cullLayer[l] = cull && l != TRANSLUCENT && (wireframe ? RenderPipelines.WIREFRAME : pipeline).isCull();
			if (cullLayer[l]) {
				cullMask |= 1 << l;
			}
		}

		if (TerrainFeatures.multiDraw()) {
			try {
				boolean trackSprites = VisibleSpriteTracker.trackingFrame();
				boolean direct = TerrainFeatures.directMultiDraw();
				boolean meshActive = !wireframe && VulkanMeshTerrainEngine.get().beginFrame();
				if (meshActive && (nearPendingCount | farPendingCount) != 0) {
					VulkanMeshTerrainEngine engine = VulkanMeshTerrainEngine.get();
					if (drainPendingMeshUploads(engine, engine.arenaEpoch())) {
						planValid = false;
					}
					meshActive = engine.isAvailable();
				}
				boolean glSingleMdi = !meshActive && !wireframe && !direct && GlSingleMdiTerrain.get().beginFrame();
				if (canReusePlan(dispatcher, epoch, visibleSections, trackSprites, direct, cullMask, meshActive, glSingleMdi)) {
					ChunkSectionsToRender reused = reuseRegional(blockAtlas, trackSprites, now, meshActive, cull, glSingleMdi);
					if (reused != null) {
						lastRegional = true;
						return reused;
					}
					meshActive = !wireframe && VulkanMeshTerrainEngine.get().isAvailable();
					glSingleMdi = !meshActive && !wireframe && !direct && GlSingleMdiTerrain.get().beginFrame();
				}
				ChunkSectionsToRender regional = prepareRegional(dispatcher, epoch, visibleSections, blockAtlas, now,
					trackSprites, direct, cullMask, meshActive, cull, glSingleMdi);
				lastRegional = true;
				return regional;
			} catch (Throwable t) {
				TerrainFeatures.disableMultiDraw(t);
				planValid = false;
				plan.begin();
			}
		}
		lastRegional = false;
		planValid = false;
		resetGroups();
		uboCount = 0;
		return prepareSections(dispatcher, epoch, visibleSections, blockAtlas, now);
	}

	/** Fog occlusion mirrors fog.glsl: fully fogged past the environmental end (spherical) or the render-distance end (cylindrical). */
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

	private boolean fogged(int ox, int oy, int oz) {
		return fogCull && FastFrustumMath.isFullyFogged(
			ox - camX - FOG_PADDING, oy - camY - FOG_PADDING, oz - camZ - FOG_PADDING,
			ox + 16 - camX + FOG_PADDING, oy + 16 - camY + FOG_PADDING, oz + 16 - camZ + FOG_PADDING,
			envEnd, rdEnd);
	}

	/**
	 * Vanilla {@code getVisibility} (eased when smooth chunk fade is on), computed inline through
	 * the mesh-swap hook with the per-frame fade gate; without the hook, vanilla's is only
	 * called while the section still fades.
	 */
	private float visibility(SectionRenderDispatcher.RenderSection section, long now) {
		if (SWAP_HOOK) {
			return ((SectionMeshSwapHook) section).ephemera$visibility(now, frameSmoothFade);
		} else if (FADE_ACCESSOR) {
			TerrainRenderSectionFadeAccessor fade = (TerrainRenderSectionFadeAccessor) section;
			if (now - fade.getEphemeraUploadedTime() >= fade.getEphemeraFadeDuration()) {
				return 1.0f;
			}
		}
		return section.getVisibility(now);
	}

	/** Bucket layout to cull with for this section layer, or null to draw the whole layer. */
	private @Nullable QuadBuckets cullBuckets(int layer, @Nullable SectionLayout layout, SectionMesh.SectionDraw draw) {
		if (!cullLayer[layer] || layout == null || draw.hasCustomIndexBuffer()) {
			return null;
		}
		QuadBuckets b = layout.buckets(layer);
		// The draw must cover exactly the bucketed quads (6 sequential indices per quad).
		return b != null && b.quadCount() * 6 == draw.indexCount() ? b : null;
	}

	private int visibleMask(QuadBuckets buckets, SectionLayout layout) {
		return buckets.visibleMask(camX - layout.sectionX, camY - layout.sectionY, camZ - layout.sectionZ, CULL_MARGIN);
	}

	// ---------------------------------------------------------------------------------------
	// Regional multi-draw
	// ---------------------------------------------------------------------------------------

	/**
	 * True when last frame's regional plan is exactly what a rebuild would produce: same
	 * dispatcher, no topology or visible-list change (counters plus an identity check of the
	 * list), same camera position, fog, culling, atlas size, sprite tracking and draw mode, and
	 * no section was still fading in when it was built.
	 */
	private static boolean ephemera$planDigestActive() {
		return MixinHealth.isApplied("SectionOcclusionGraphDigestMixin")
			&& MixinHealth.isApplied("TerrainLevelRendererChunkBatchMixin")
			&& !MixinHealth.hasFailed("SectionOcclusionGraphDigestMixin");
	}

	private boolean canReusePlan(SectionRenderDispatcher dispatcher, int epoch,								 ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
								 boolean trackSprites, boolean direct, int cullMask, boolean meshActive,
								 boolean glSingleMdi) {
		if (!planValid || !TerrainFeatures.planReuse()
			|| dispatcher != planDispatcher || epoch != planEpoch
			|| planTopology != TerrainTopology.topology()
			|| hasCamera != planHasCamera
			|| (!planCamIndependent && (camX != planCamX || camY != planCamY || camZ != planCamZ))
			|| fogCull != planFogCull || Float.compare(envEnd, planEnvEnd) != 0 || Float.compare(rdEnd, planRdEnd) != 0
			|| cullMask != planCullMask || atlasWidth != planAtlasWidth || atlasHeight != planAtlasHeight
			|| trackSprites != planTrackSprites || direct != planDirect || meshActive != planMeshActive
			|| glSingleMdi != planGlSingleMdi
			|| (glSingleMdi && GlSingleMdiTerrain.get().supportsTranslucent() != planGlSingleMdiTranslucent)
			|| !plan.reusable()) {
			return false;
		}
		int size = visibleSections.size();
		if (size != planSectionCount) {
			return false;
		}
		// O(1) fast path: the wave-3 rolling digest is order-sensitive over the same packed
		// section nodes the plan captured, so a matching snapshot proves this frame's refilled
		// list is the plan's list (nodes are position-unique, so identical nodes+order implies
		// identical section references). The element walk stays as the digest-inactive fallback.
		if (planDigestSize == size
			&& planDigest1 == TerrainTopology.visibleSectionsDigest1
			&& planDigest2 == TerrainTopology.visibleSectionsDigest2) {
			return true;
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

	/** Re-arms last frame's plan: fresh section uniforms (this frame's model-view + fading alpha) and sprite stamps, nothing else. */
	private @Nullable ChunkSectionsToRender reuseRegional(GpuTextureView blockAtlas, boolean trackSprites,
														  long now, boolean meshActive, boolean dirCull, boolean glSingleMdi) {
		boolean anySettled = false;
		if (glSingleMdi && glMdiFadingCount > 0) {
			float[] vis = glMdiVisScratch;
			for (int i = 0; i < glMdiFadingCount; i++) {
				float v = visibility(glMdiFadingSections[i], now);
				if (v == 1.0f) {
					anySettled = true;
				}
				vis[i] = v;
			}
			GlSingleMdiTerrain.get().prepareReuseForFading(glMdiFadingEntries, vis, glMdiFadingCount);
		}
		for (int i = 0; i < fadingCount; i++) {
			float vis = visibility(fadingSections[i], now);
			if (vis == 1.0f) {
				anySettled = true;
			}
			int ubo = fadingUbos[i];
			exactInfos[ubo] = sectionInfo(fadingCaches[i], fadingLayouts[i], fadingOrigins[i], vis);
		}
		if (anySettled) {
			// Rebuild on the next frame so newly settled sections merge into their shared region UBO / entry.
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
			if (!VulkanMeshTerrainEngine.get().prepareFrame(
				meshSolidTasks, meshSolidTaskCount,
				meshCutoutTasks, meshCutoutTaskCount,
				meshTaskVersion, meshActiveLayerMask,
				meshVisUpdateSlots, meshVisUpdateValues, visUpdateCount,
				frameModelView, camProjection, camBlockX, camBlockY, camBlockZ,
				camOffX, camOffY, camOffZ, atlasWidth, atlasHeight,
				fogCull, envEnd, rdEnd, dirCull)) {
				planValid = false;
				return null;
			}
		}
		GpuBufferSlice[] slices = uboCount == 0 ? NO_SLICES : RenderSystem.getDynamicUniforms().writeChunkSections(exactInfos);
		if (!plan.reuse(blockAtlas, slices)) {
			return null;
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
		ChunkSectionsToRender result = new ChunkSectionsToRender(blockAtlas, drawGroups, plan.maxIndicesRequired(), slices);
		plan.bind(result);
		return result;
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

	private void addMeshSolidTask(int taskWord) {
		if (meshSolidTaskCount == meshSolidTasks.length) {
			meshSolidTasks = java.util.Arrays.copyOf(meshSolidTasks, meshSolidTasks.length * 2);
		}
		meshSolidTasks[meshSolidTaskCount++] = taskWord;
	}

	private void addMeshCutoutTask(int taskWord) {
		if (meshCutoutTaskCount == meshCutoutTasks.length) {
			meshCutoutTasks = java.util.Arrays.copyOf(meshCutoutTasks, meshCutoutTasks.length * 2);
		}
		meshCutoutTasks[meshCutoutTaskCount++] = taskWord;
	}

	private void enqueueMeshSectionTasks(VulkanMeshSectionHandle h, int handledLayers, boolean taskMode) {
		meshSectionCount++;
		if (!taskMode) {
			addMeshSolidTask(h.slot);
			return;
		}
		int slotPrefix = h.slot << 8;
		if ((handledLayers & 1) != 0 && h.solidMeshletCount > 0) {
			int chunks = (h.solidMeshletCount + 127) >>> 7;
			for (int c = 0; c < chunks; c++) {
				addMeshSolidTask(slotPrefix | c);
			}
		}
		if ((handledLayers & 2) != 0 && h.cutoutMeshletCount > 0) {
			int chunks = (h.cutoutMeshletCount + 127) >>> 7;
			for (int c = 0; c < chunks; c++) {
				addMeshCutoutTask(slotPrefix | c);
			}
		}
	}

	private void clearFading() {
		if (fadingCount > 0) {
			java.util.Arrays.fill(fadingSections, 0, fadingCount, null);
			java.util.Arrays.fill(fadingCaches, 0, fadingCount, null);
			java.util.Arrays.fill(fadingLayouts, 0, fadingCount, null);
			java.util.Arrays.fill(fadingOrigins, 0, fadingCount, null);
			fadingCount = 0;
		}
	}

	private void trackFading(SectionRenderDispatcher.RenderSection section, SectionMeshRenderCache cache,
							 @Nullable SectionLayout layout, @Nullable BlockPos origin, int ubo) {
		int n = fadingCount;
		if (n == fadingSections.length) {
			int next = n * 2;
			fadingSections = java.util.Arrays.copyOf(fadingSections, next);
			fadingCaches = java.util.Arrays.copyOf(fadingCaches, next);
			fadingLayouts = java.util.Arrays.copyOf(fadingLayouts, next);
			fadingOrigins = java.util.Arrays.copyOf(fadingOrigins, next);
			fadingUbos = java.util.Arrays.copyOf(fadingUbos, next);
		}
		fadingSections[n] = section;
		fadingCaches[n] = cache;
		fadingLayouts[n] = layout;
		fadingOrigins[n] = origin;
		fadingUbos[n] = ubo;
		fadingCount = n + 1;
	}

	private void clearGlMdiFading() {
		if (glMdiFadingCount > 0) {
			java.util.Arrays.fill(glMdiFadingSections, 0, glMdiFadingCount, null);
			glMdiFadingCount = 0;
		}
	}

	private void trackGlMdiFading(SectionRenderDispatcher.RenderSection section, int entry) {
		int n = glMdiFadingCount;
		if (n == glMdiFadingSections.length) {
			int next = n * 2;
			glMdiFadingSections = java.util.Arrays.copyOf(glMdiFadingSections, next);
			glMdiFadingEntries = java.util.Arrays.copyOf(glMdiFadingEntries, next);
			glMdiVisScratch = java.util.Arrays.copyOf(glMdiVisScratch, next);
		}
		glMdiFadingSections[n] = section;
		glMdiFadingEntries[n] = entry;
		glMdiFadingCount = n + 1;
	}

	/** Uniform slot 0 of a single multi-draw frame: this frame's model-view, origin 0, fully visible. */
	private DynamicUniforms.ChunkSectionInfo glSingleMdiFrameInfo() {
		DynamicUniforms.ChunkSectionInfo info = glSingleMdiFrameInfo;
		if (info == null || info.modelView() != frameModelView
			|| info.textureAtlasWidth() != atlasWidth || info.textureAtlasHeight() != atlasHeight) {
			info = new DynamicUniforms.ChunkSectionInfo(frameModelView, 0, 0, 0, 1.0f, atlasWidth, atlasHeight);
			glSingleMdiFrameInfo = info;
		}
		return info;
	}

	private ChunkSectionsToRender prepareRegional(SectionRenderDispatcher dispatcher, int epoch,
												  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
												  GpuTextureView blockAtlas, long now, boolean trackSprites,
												  boolean direct, int cullMask, boolean meshActive, boolean dirCull,
												  boolean glSingleMdi) {
		// Read the counters before looking at any section: a change racing the build then
		// only causes one extra rebuild, never a stale reuse.
		long topology = TerrainTopology.topology();
		planValid = false;
		// Reuse fading arrays in place across rebuilds and only null shrinking tails at method end
		// to avoid millions of G1 SATB write barriers during rapid camera motion + concurrent marking.
		int prevFadingCount = fadingCount;
		fadingCount = 0;
		int prevMeshFadingCount = meshFadingCount;
		meshFadingCount = 0;
		meshSolidTaskCount = 0;
		meshCutoutTaskCount = 0;
		meshSectionCount = 0;
		meshActiveLayerMask = 0;
		int meshVisUpdates = 0;
		VulkanMeshTerrainEngine meshEngine = meshActive ? VulkanMeshTerrainEngine.get() : null;
		boolean meshTaskMode = meshEngine != null && meshEngine.usesTaskShader();
		int meshEpoch = meshEngine != null ? meshEngine.arenaEpoch() : -1;
		if (meshEpoch != lastMeshScanEpoch) {
			clearPendingMeshUploads(false);
		}
		if (!lastRegional) {
			resetGroups();
		}
		uboCount = 0;
		plan.begin();
		regionUbos.clear();
		spriteSections.clear();
		long lastRegionKey = Long.MIN_VALUE;
		int lastRegionUbo = -1;
		int prevGlMdiFadingCount = glMdiFadingCount;
		glMdiFadingCount = 0;
		regionBatchEntries.clear();
		GlSingleMdiTerrain singleMdi = glSingleMdi ? GlSingleMdiTerrain.get() : null;
		boolean translucentEntries = singleMdi != null && singleMdi.supportsTranslucent();
		long lastEntryKey = Long.MIN_VALUE;
		int lastEntry = -1;
		if (singleMdi != null) {
			singleMdi.beginBuild();
			// Slot 0: shared by every batch-entry draw, whose origin and visibility come from the entry.
			putInfo(uboCount++, glSingleMdiFrameInfo());
		}
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
		FrameBudgetPacer budgetPacer = EphemeraPacerRuntime.budget();
		boolean streamingBudgetChecked = false;
		boolean streamingBudgetActive = false;
		int spriteGeneration = trackSprites ? VisibleSpriteTracker.generation().id : 0;
		boolean useSwapHook = SWAP_HOOK;
		boolean usedCpuDirCull = false;

		// Pre-upload newly compiled Vulkan mesh sections OUTSIDE dispatcher.lock() so chunk builder
		// worker threads never block on VMA / staging-buffer copies (eliminates lock inversion spikes).
		if (meshEngine != null && (topology != lastMeshScanTopology || meshEpoch != lastMeshScanEpoch)) {
			lastMeshScanTopology = topology;
			lastMeshScanEpoch = meshEpoch;
			// Probe full availability once (encoder bridge check); mid-scan only the permanent
			// failure flag can flip, so keep per-section work to that cheap field read.
			boolean meshAvailable = meshEngine.isAvailable();
			for (int i = 0; i < size && meshAvailable && !meshEngine.isFailed(); i++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
				SectionMeshRenderCache cache = useSwapHook
					? ((SectionMeshSwapHook) section).ephemera$compiledCache()
					: (section.getSectionMesh() instanceof SectionMeshRenderCache c ? c : null);
				if (cache == null) {
					continue;
				}
				VulkanMeshSectionHandle existing = cache.ephemera$meshHandle();
				if (existing != null && !existing.released && existing.arenaEpoch == meshEpoch) {
					continue;
				}
				SectionLayout preLayout = cache.ephemera$layout();
				if (preLayout == null || preLayout.meshlets == null) {
					continue;
				}
				if (doFog) {
					double fMinX = preLayout.sectionX - cxMin, fMaxX = preLayout.sectionX - cxMax;
					double dx = fMinX > 0.0 ? fMinX : (fMaxX < 0.0 ? -fMaxX : 0.0);
					double fMinZ = preLayout.sectionZ - czMin, fMaxZ = preLayout.sectionZ - czMax;
					double dz = fMinZ > 0.0 ? fMinZ : (fMaxZ < 0.0 ? -fMaxZ : 0.0);
					double horizSq = dx * dx + dz * dz;
					if (checkRdFog && horizSq >= rdSq) {
						continue;
					}
					double fMinY = preLayout.sectionY - cyMin, fMaxY = preLayout.sectionY - cyMax;
					double dy = fMinY > 0.0 ? fMinY : (fMaxY < 0.0 ? -fMaxY : 0.0);
					if ((checkRdFog && dy >= rdVal) || (checkEnvFog && horizSq + dy * dy >= envSq)) {
						continue;
					}
				}
				if (!streamingBudgetChecked) {
					streamingBudgetActive = EphemeraPacerRuntime.beginFrameStreamingBudget();
					streamingBudgetChecked = true;
				}
				float vis = visibility(section, now);
				double sdx = (preLayout.sectionX + 8.0) - cx;
				double sdy = (preLayout.sectionY + 8.0) - cy;
				double sdz = (preLayout.sectionZ + 8.0) - cz;
				boolean near = (sdx * sdx + sdy * sdy + sdz * sdz) <= 2304.0;
				int priorityFloor = near ? 2 : 1;
				if (!streamingBudgetActive || budgetPacer.allowStreamingWork(priorityFloor)) {
					long tUp = streamingBudgetActive ? System.nanoTime() : 0L;
					VulkanMeshSectionHandle uploaded = meshEngine.uploadSection(preLayout.meshlets, vis);
					if (streamingBudgetActive) {
						budgetPacer.recordStreamingWork(System.nanoTime() - tUp);
					}
					cache.ephemera$setMeshHandle(uploaded);
					if (uploaded != null) {
						long freed = preLayout.releaseCpuMeshlets();
						if (freed > 0L) {
							MemoryPressurePolicy.recordHeapReclaimed(freed);
						}
					}
				} else {
					enqueuePendingMeshUpload(cache, vis, near);
				}
			}
			if (!meshEngine.isAvailable()) {
				meshEngine = null;
				meshActive = false;
			}
		}

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

				float visibility = visibility(section, now);
				SectionLayout layout = null;

				// Vulkan VK_EXT_mesh_shader fast path: SOLID & CUTOUT render in 1 GPU-driven draw call
				// with zero per-section UBOs (even when fading!), leaving only TRANSLUCENT or unbaked
				// layers for the indirect batch plan.
				if (meshEngine != null) {
					VulkanMeshSectionHandle h = meshReady ? existingMesh : null;
					if (h == null) {
						layout = cache.ephemera$layout();
						if (layout != null && layout.meshlets != null) {
							// Never run Vulkan VMA/staging mesh uploads while holding dispatcher.lock();
							// defer any section that entered visibleSections without a topology bump to
							// the unlocked frame-head drain while drawing its indexed mesh this frame.
							double sdx = (layout.sectionX + 8.0) - cx;
							double sdy = (layout.sectionY + 8.0) - cy;
							double sdz = (layout.sectionZ + 8.0) - cz;
							boolean near = (sdx * sdx + sdy * sdy + sdz * sdz) <= 2304.0;
							enqueuePendingMeshUpload(cache, visibility, near);
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
					// Single multi-draw: sequentially indexed solid/cutout layers (and the translucent
					// layer, when its program has the block) use slot 0 plus one batch entry per
					// section (shared per region once settled, own while fading or unbaked).
					int entryMask = 0;
					int firstInstance = 0;
					if (singleMdi != null) {
						entryMask = mask & GL_MDI_LAYERS & ~state.customIndexMask;
						if (translucentEntries) {
							entryMask |= mask & GL_MDI_TRANSLUCENT;
						}
						if (entryMask != 0) {
							int entry;
							if (regionShared) {
								long key = layout.regionKey;
								if (key == lastEntryKey) {
									entry = lastEntry;
								} else {
									entry = regionBatchEntries.get(key);
									if (entry < 0) {
										entry = singleMdi.addEntry(layout.originX, layout.originY, layout.originZ, 1.0f);
										if (entry >= 0) {
											regionBatchEntries.put(key, entry);
										}
									}
									lastEntryKey = key;
									lastEntry = entry;
								}
							} else {
								if (origin == null && layout == null) {
									origin = section.getRenderOrigin();
								}
								entry = layout != null
									? singleMdi.addEntry(layout.originX, layout.originY, layout.originZ, visibility)
									: singleMdi.addEntry(origin.getX(), origin.getY(), origin.getZ(), visibility);
								if (entry >= 0 && visibility != 1.0f) {
									trackGlMdiFading(section, entry);
								}
							}
							if (entry >= 0) {
								firstInstance = entry + 1;
							} else {
								// Block full: these layers fall back to a section or region uniform.
								entryMask = 0;
							}
						}
					}
					if (!regionShared && visibility == 1.0f) {
						unbakedCount++;
					}

					// Settled region-baked sections share their region's slot; fading or unbaked
					// sections get their own, positioned at the origin their vertices are relative to.
					// Only layers not drawn through a batch entry need one.
					int ubo = 0;
					if ((mask & ~entryMask) != 0) {
						if (regionShared) {
							long key = layout.regionKey;
							if (key == lastRegionKey) {
								ubo = lastRegionUbo;
							} else {
								ubo = regionUbo(layout);
								lastRegionKey = key;
								lastRegionUbo = ubo;
							}
						} else {
							ubo = uboCount++;
							if (origin == null && layout == null) {
								origin = section.getRenderOrigin();
							}
							addSectionInfo(cache, ubo, layout, origin, visibility);
							if (visibility != 1.0f) {
								trackFading(section, cache, layout, origin, ubo);
							}
						}
					}

					if (cullMask != 0 && (cx != state.lastCamX || cy != state.lastCamY || cz != state.lastCamZ)) {
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
						boolean viaEntry = (entryMask & bit) != 0;
						int layerUbo = viaEntry ? 0 : ubo;
						int layerInstance = viaEntry ? firstInstance : 0;
						if ((custom & bit) != 0) {
							plan.addIndexed(l, layerUbo, vertexBuffer, state.indexBuffers[l], state.indexTypes[l],
								state.firstIndices[l], state.indexCounts[l], baseVertex, layerInstance);
							continue;
						}
						QuadBuckets buckets = (cullMask & bit) != 0 ? state.cullBuckets[l] : null;
						if (buckets != null) {
							usedCpuDirCull = true;
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
								plan.addQuadSpans(l, layerUbo, vertexBuffer, baseVertex, state.cachedSpans, spanOff, n, layerInstance);
							}
						} else {
							plan.addSequential(l, layerUbo, vertexBuffer, baseVertex, state.indexCounts[l], layerInstance);
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
		// Null only the shrinking tail of reused fading arrays so unloaded sections can be GC'd
		// without clearing and refilling active slots on every rebuild frame.
		if (fadingCount < prevFadingCount) {
			java.util.Arrays.fill(fadingSections, fadingCount, prevFadingCount, null);
			java.util.Arrays.fill(fadingCaches, fadingCount, prevFadingCount, null);
			java.util.Arrays.fill(fadingLayouts, fadingCount, prevFadingCount, null);
			java.util.Arrays.fill(fadingOrigins, fadingCount, prevFadingCount, null);
		}
		if (meshFadingCount < prevMeshFadingCount) {
			java.util.Arrays.fill(meshFadingSections, meshFadingCount, prevMeshFadingCount, null);
			java.util.Arrays.fill(meshFadingHandles, meshFadingCount, prevMeshFadingCount, null);
		}
		if (glMdiFadingCount < prevGlMdiFadingCount) {
			java.util.Arrays.fill(glMdiFadingSections, glMdiFadingCount, prevGlMdiFadingCount, null);
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
			if (!meshEngine.prepareFrame(
				meshSolidTasks, meshSolidTaskCount,
				meshCutoutTasks, meshCutoutTaskCount,
				meshTaskVersion, meshActiveLayerMask,
				meshVisUpdateSlots, meshVisUpdateValues, meshVisUpdates,
				frameModelView, camProjection, camBlockX, camBlockY, camBlockZ,
				camOffX, camOffY, camOffZ, atlasWidth, atlasHeight,
				fogCull, envEnd, rdEnd, dirCull)) {
				// Same-frame indexed fallback so no opaque terrain disappears on mesh failure.
				return prepareRegional(dispatcher, epoch, visibleSections, blockAtlas, now,
					trackSprites, direct, cullMask, false, dirCull, glSingleMdi);
			}
		}

		if (singleMdi != null) {
			singleMdi.finishBuild();
		}
		lastSectionCount = uboCount;
		lastFogCulled = fogCulled;
		lastUnbakedCount = unbakedCount;
		lastBatchEntries = singleMdi != null ? singleMdi.entryCount() : 0;
		rebuiltFrames++;
		if (DIAG && now - lastDiagMs >= 2000L) {
			lastDiagMs = now;
			TerrainFeatures.LOGGER.info("TerrainDiag: visible={} fogCulled={} meshSec={} meshTasks={}/{} ubos={} mdiEntries={} batches={} cmds={} fading={} mdiFading={} unbaked={} reused={} rebuilt={}",
				size, fogCulled, meshSectionCount, meshSolidTaskCount, meshCutoutTaskCount, uboCount, lastBatchEntries, plan.lastBatchCount(), plan.lastCommandCount(), fadingCount, glMdiFadingCount, unbakedCount, reusedFrames, rebuiltFrames);
		}
		GpuBufferSlice[] slices = uboCount == 0 ? NO_SLICES : RenderSystem.getDynamicUniforms().writeChunkSections(exactInfos(uboCount));
		plan.finish(blockAtlas, slices, direct, singleMdi != null);
		// Vanilla's body never runs for this object (the renderGroup hook takes it), but keep the
		// draw groups valid and empty for anything else that inspects them.
		ChunkSectionsToRender result = new ChunkSectionsToRender(blockAtlas, drawGroups, plan.maxIndicesRequired(), slices);
		plan.bind(result);

		// Remember what this plan was built from.
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
		// Snapshot the refill content digest: the rolling digest is order-sensitive over the same
		// packed section nodes, so a matching snapshot proves this frame's list is the plan's list.
		planDigest1 = TerrainTopology.visibleSectionsDigest1;
		planDigest2 = TerrainTopology.visibleSectionsDigest2;
		planDigestSize = ephemera$planDigestActive() ? size : -1;
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
		planDirect = direct;
		planMeshActive = meshActive;
		planCamIndependent = !usedCpuDirCull && (!fogCull || fogCulled == 0);
		planGlSingleMdi = glSingleMdi;
		planGlSingleMdiTranslucent = translucentEntries;
		// Deferred mesh sections already have valid indexed draws in this plan; pending uploads
		// drain separately at frame head without forcing a full draw-plan rebuild every frame.
		planValid = true;
		return result;
	}

	private void clearPendingMeshUploads(boolean trim) {
		if (nearPendingCount > 0) {
			java.util.Arrays.fill(nearPendingCaches, 0, nearPendingCount, null);
			nearPendingCount = 0;
		}
		if (farPendingCount > 0) {
			java.util.Arrays.fill(farPendingCaches, 0, farPendingCount, null);
			farPendingCount = 0;
		}
		pendingPromotedCount = 0;
		if (trim) {
			if (nearPendingCaches.length > 2048) {
				nearPendingCaches = new SectionMeshRenderCache[32];
				nearPendingVis = new float[32];
			}
			if (farPendingCaches.length > 4096) {
				farPendingCaches = new SectionMeshRenderCache[64];
				farPendingVis = new float[64];
			}
		}
	}

	private void enqueuePendingMeshUpload(SectionMeshRenderCache cache, float vis, boolean near) {
		if (near) {
			int n = nearPendingCount;
			if (n == nearPendingCaches.length) {
				int next = n * 2;
				nearPendingCaches = java.util.Arrays.copyOf(nearPendingCaches, next);
				nearPendingVis = java.util.Arrays.copyOf(nearPendingVis, next);
			}
			nearPendingCaches[n] = cache;
			nearPendingVis[n] = vis;
			nearPendingCount = n + 1;
		} else {
			int n = farPendingCount;
			if (n == farPendingCaches.length) {
				int next = n * 2;
				farPendingCaches = java.util.Arrays.copyOf(farPendingCaches, next);
				farPendingVis = java.util.Arrays.copyOf(farPendingVis, next);
			}
			farPendingCaches[n] = cache;
			farPendingVis[n] = vis;
			farPendingCount = n + 1;
		}
	}

	/**
	 * Drains pending Vulkan mesh uploads in O(1) per section (nearby first, then distant) under the
	 * shared frame streaming budget. Returns {@code true} when the cached draw plan should rebuild
	 * to promote uploaded sections into the mesh-shader draw list (immediately for nearby terrain
	 * or queue completion, batched for distant terrain to avoid per-frame draw-plan thrash).
	 */
	private boolean drainPendingMeshUploads(VulkanMeshTerrainEngine meshEngine, int meshEpoch) {
		if (!meshEngine.isAvailable()) {
			return false;
		}
		boolean budgetActive = EphemeraPacerRuntime.beginFrameStreamingBudget();
		FrameBudgetPacer budgetPacer = EphemeraPacerRuntime.budget();
		int uploaded = 0;
		boolean promotedNear = false;
		while ((nearPendingCount | farPendingCount) != 0 && !meshEngine.isFailed()) {
			boolean near = nearPendingCount > 0;
			int priorityFloor = near ? 2 : 1;
			if (budgetActive && !budgetPacer.allowStreamingWork(priorityFloor)) {
				break;
			}
			SectionMeshRenderCache cache;
			float vis;
			if (near) {
				int idx = --nearPendingCount;
				cache = nearPendingCaches[idx];
				vis = nearPendingVis[idx];
				nearPendingCaches[idx] = null;
			} else {
				int idx = --farPendingCount;
				cache = farPendingCaches[idx];
				vis = farPendingVis[idx];
				farPendingCaches[idx] = null;
			}
			if (cache == null) {
				continue;
			}
			VulkanMeshSectionHandle existing = cache.ephemera$meshHandle();
			if (existing != null && !existing.released && existing.arenaEpoch == meshEpoch) {
				continue;
			}
			SectionLayout layout = cache.ephemera$layout();
			if (layout == null || layout.meshlets == null) {
				continue;
			}
			long tUp = budgetActive ? System.nanoTime() : 0L;
			VulkanMeshSectionHandle h = meshEngine.uploadSection(layout.meshlets, vis);
			if (budgetActive) {
				budgetPacer.recordStreamingWork(System.nanoTime() - tUp);
			}
			cache.ephemera$setMeshHandle(h);
			if (h != null) {
				uploaded++;
				if (near) {
					promotedNear = true;
				}
				long freed = layout.releaseCpuMeshlets();
				if (freed > 0L) {
					MemoryPressurePolicy.recordHeapReclaimed(freed);
				}
			}
		}
		if (uploaded <= 0) {
			return false;
		}
		pendingPromotedCount += uploaded;
		return promotedNear || (nearPendingCount | farPendingCount) == 0 || pendingPromotedCount >= DISTANT_PROMOTE_BATCH;
	}

	/** UBO slot shared by every settled region-baked section of a region; its info record is reused across frames. */
	private int regionUbo(SectionLayout layout) {
		long key = layout.regionKey;
		int slot = regionUbos.get(key);
		if (slot != -1) {
			return slot;
		}
		slot = uboCount++;
		DynamicUniforms.ChunkSectionInfo info = regionInfos.get(key);
		if (info == null || info.textureAtlasWidth() != atlasWidth || info.textureAtlasHeight() != atlasHeight) {
			if (regionInfos.size() >= MAX_REGION_INFOS) {
				regionInfos.clear();
			}
			// The shared matrix is written into the UBO immediately by writeChunkSections, so
			// every record can reference the same instance across frames.
			info = new DynamicUniforms.ChunkSectionInfo(frameModelView, layout.originX, layout.originY, layout.originZ,
				1.0f, atlasWidth, atlasHeight);
			regionInfos.put(key, info);
		}
		putInfo(slot, info);
		regionUbos.put(key, slot);
		return slot;
	}

	// ---------------------------------------------------------------------------------------
	// Per-section fallback
	// ---------------------------------------------------------------------------------------

	private ChunkSectionsToRender prepareSections(SectionRenderDispatcher dispatcher, int epoch,
												  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
												  GpuTextureView blockAtlas, long now) {
		boolean trackSprites = VisibleSpriteTracker.trackingFrame();
		int fogCulled = 0;
		int largestIndexCount = 0;
		boolean useSwapHook = SWAP_HOOK;
		dispatcher.lock();
		try {
			Object[] elements = visibleSections.elements();
			int size = visibleSections.size();
			for (int i = 0; i < size; i++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
				SectionMeshRenderCache cache = useSwapHook
					? ((SectionMeshSwapHook) section).ephemera$compiledCache()
					: (section.getSectionMesh() instanceof SectionMeshRenderCache c ? c : null);
				if (cache == null || !cache.ephemera$hasDraws()) {
					continue;
				}
				BlockPos origin = section.getRenderOrigin();
				if (fogged(origin.getX(), origin.getY(), origin.getZ())) {
					fogCulled++;
					continue;
				}
				SectionLayout layout = cache.ephemera$layout();

				int uboIndex = -1;
				for (int l = 0; l < LAYER_COUNT; l++) {
					SectionMesh.SectionDraw draw = cache.ephemera$draw(l);
					if (draw == null) {
						continue;
					}
					TlsfAllocator.Allocation vertex = cache.ephemera$allocation(SectionMeshRenderCache.vertexSlot(l), epoch);
					if (vertex == null) {
						continue;
					}
					boolean customIndex = draw.hasCustomIndexBuffer();
					TlsfAllocator.Allocation index = null;
					if (customIndex) {
						index = cache.ephemera$allocation(SectionMeshRenderCache.indexSlot(l), epoch);
						if (index == null) {
							continue;
						}
					}

					// Directional culling narrows the draw to the range covering the visible buckets.
					int startQuad = 0;
					int indexCount = draw.indexCount();
					QuadBuckets buckets = cullBuckets(l, layout, draw);
					if (buckets != null) {
						long span = buckets.coveringSpan(visibleMask(buckets, layout));
						startQuad = (int) (span >>> 32);
						indexCount = ((int) span - startQuad) * 6;
						if (indexCount == 0) {
							continue;
						}
					}

					if (uboIndex == -1) {
						uboIndex = uboCount++;
						addSectionInfo(cache, uboIndex, layout, origin, visibility(section, now));
					}

					int baseVertex = (int) (vertex.getOffsetFromHeap() / vertexSizes[l]) + startQuad * SectionVertexBaker.VERTICES_PER_QUAD;
					RenderPass.Draw<GpuBufferSlice[]> d = cache.ephemera$cachedDraw(l, uboIndex, vertex, index);
					if (d == null || d.baseVertex() != baseVertex || d.indexCount() != indexCount) {
						d = buildDraw(draw, vertex, index, uboIndex, baseVertex, indexCount);
						cache.ephemera$cacheDraw(l, uboIndex, vertex, index, d);
					}
					if (!customIndex && indexCount > largestIndexCount) {
						largestIndexCount = indexCount;
					}

					// Same grouping key as vanilla: translucent draws stay in one ordered list.
					int combinedHash = 173;
					if (l != TRANSLUCENT) {
						combinedHash = 31 * combinedHash + d.vertexBuffer().hashCode();
						if (customIndex) {
							combinedHash = 31 * combinedHash + d.indexBuffer().hashCode();
							combinedHash = 31 * combinedHash + d.indexType().hashCode();
						}
					}
					groupList(l, combinedHash).add(d);
				}
				if (uboIndex != -1 && trackSprites) {
					VisibleSpriteTracker.markSection(cache, now);
				}
			}
		} finally {
			dispatcher.unlock();
		}

		lastSectionCount = uboCount;
		lastFogCulled = fogCulled;
		GpuBufferSlice[] chunkSectionInfos = RenderSystem.getDynamicUniforms().writeChunkSections(exactInfos(uboCount));
		return new ChunkSectionsToRender(blockAtlas, drawGroups, largestIndexCount, chunkSectionInfos);
	}

	private RenderPass.Draw<GpuBufferSlice[]> buildDraw(SectionMesh.SectionDraw draw, TlsfAllocator.Allocation vertex,
														TlsfAllocator.@Nullable Allocation index, int uboIndex,
														int baseVertex, int indexCount) {
		GpuBuffer vertexBuffer = heapBuffer(vertex);
		GpuBuffer indexBuffer = null;
		IndexType indexType = null;
		int firstIndex = 0;
		if (index != null) {
			indexBuffer = heapBuffer(index);
			indexType = draw.indexType();
			firstIndex = (int) (index.getOffsetFromHeap() / indexType.bytes);
		}
		return new RenderPass.Draw<>(0, vertexBuffer, indexBuffer, indexType, firstIndex, indexCount, baseVertex, uploader(uboIndex));
	}

	private static GpuBuffer heapBuffer(TlsfAllocator.Allocation allocation) {
		return SectionDrawState.heapBuffer(allocation);
	}

	/** Section UBO record; a baked mesh is positioned at the origin its vertices are relative to. */
	private void addSectionInfo(SectionMeshRenderCache cache, int uboIndex, @Nullable SectionLayout layout,
								BlockPos origin, float visibility) {
		putInfo(uboIndex, sectionInfo(cache, layout, origin, visibility));
	}

	private DynamicUniforms.ChunkSectionInfo sectionInfo(SectionMeshRenderCache cache, @Nullable SectionLayout layout,
														 @Nullable BlockPos origin, float visibility) {
		int x = layout != null ? layout.originX : origin.getX();
		int y = layout != null ? layout.originY : origin.getY();
		int z = layout != null ? layout.originZ : origin.getZ();
		DynamicUniforms.ChunkSectionInfo info = cache.ephemera$cachedInfo();
		if (info == null || info.modelView() != frameModelView || info.x() != x || info.y() != y || info.z() != z
			|| Float.compare(info.visibility(), visibility) != 0
			|| info.textureAtlasWidth() != atlasWidth || info.textureAtlasHeight() != atlasHeight) {
			// Only cache settled (visibility == 1.0f) records on long-lived CompiledSectionMesh
			// instances; transient fading visibility records change every millisecond and would
			// otherwise create cross-generational old->young card-table references in G1GC.
			info = new DynamicUniforms.ChunkSectionInfo(frameModelView, x, y, z, visibility, atlasWidth, atlasHeight);
			if (visibility == 1.0f) {
				cache.ephemera$cacheInfo(info);
			}
		}
		return info;
	}

	private void putInfo(int uboIndex, DynamicUniforms.ChunkSectionInfo info) {
		if (uboIndex == infos.length) {
			infos = java.util.Arrays.copyOf(infos, infos.length * 2);
		}
		infos[uboIndex] = info;
	}

	private DynamicUniforms.ChunkSectionInfo[] exactInfos(int count) {
		if (exactInfos.length != count) {
			exactInfos = new DynamicUniforms.ChunkSectionInfo[count];
		}
		System.arraycopy(infos, 0, exactInfos, 0, count);
		return exactInfos;
	}

	private BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader(int uboIndex) {
		if (uboIndex >= uploaders.length) {
			uploaders = java.util.Arrays.copyOf(uploaders, Math.max(uboIndex + 1, uploaders.length * 2));
		}
		BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> u = uploaders[uboIndex];
		if (u == null) {
			u = (sectionUbos, uniformUploader) -> uniformUploader.upload("ChunkSection", sectionUbos[uboIndex]);
			uploaders[uboIndex] = u;
		}
		return u;
	}

	private List<RenderPass.Draw<GpuBufferSlice[]>> groupList(int layer, int key) {
		Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> map = groups[layer];
		List<RenderPass.Draw<GpuBufferSlice[]>> list = map.get(key);
		if (list == null) {
			list = new ArrayList<>();
			map.put(key, list);
		}
		return list;
	}

	private void resetGroups() {
		for (Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> map : groups) {
			if (map.size() > MAX_GROUPS_BEFORE_RESET) {
				map.clear();
			} else {
				for (List<RenderPass.Draw<GpuBufferSlice[]>> list : map.values()) {
					list.clear();
				}
			}
		}
		// Release references from last frame's section infos.
		java.util.Arrays.fill(exactInfos, null);
	}
}
