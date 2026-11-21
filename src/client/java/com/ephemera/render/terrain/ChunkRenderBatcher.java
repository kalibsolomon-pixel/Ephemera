package com.ephemera.render.terrain;

import com.ephemera.core.terrain.FastFrustumMath;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.core.terrain.SectionVertexBaker;
import com.ephemera.mixin.client.terrain.TerrainFogRendererAccessor;
import com.ephemera.mixin.client.terrain.TerrainRenderSectionFadeAccessor;
import com.ephemera.render.terrain.vulkan.VulkanMeshSectionHandle;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
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
	private long lastSpriteStamp = Long.MIN_VALUE / 4;

	// Fading sections inside a reusable plan: only their visibility uniform changes frame-to-frame.
	private SectionRenderDispatcher.RenderSection[] fadingSections = new SectionRenderDispatcher.RenderSection[32];
	private SectionMeshRenderCache[] fadingCaches = new SectionMeshRenderCache[32];
	private @Nullable SectionLayout[] fadingLayouts = new SectionLayout[32];
	private @Nullable BlockPos[] fadingOrigins = new BlockPos[32];
	private int[] fadingUbos = new int[32];
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

	@SuppressWarnings("unchecked")
	private ChunkRenderBatcher() {
		groups = new Int2ObjectOpenHashMap[LAYER_COUNT];
		for (ChunkSectionLayer layer : LAYERS) {
			Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> map = new Int2ObjectOpenHashMap<>();
			groups[layer.ordinal()] = map;
			drawGroups.put(layer, map);
		}
		regionUbos.defaultReturnValue(-1);
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
		VulkanMeshTerrainEngine.get().resetSections();
		resetGroups();
		planValid = false;
		planDispatcher = null;
		java.util.Arrays.fill(planSections, null);
		planSectionCount = 0;
		clearFading();
		clearMeshFading();
		meshSolidTaskCount = 0;
		meshCutoutTaskCount = 0;
		meshSectionCount = 0;
		meshActiveLayerMask = 0;
		spriteSections.clear();
		regionInfos.clear();
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

	private ChunkSectionsToRender prepare(SectionRenderDispatcher dispatcher, int epoch,
										  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
										  Matrix4fc modelViewMatrix, TextureManager textureManager,
										  LevelRenderState levelRenderState) {
		long now = Util.getMillis();
		VisibleSpriteTracker.beginFrame(now);

		GpuTextureView blockAtlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		atlasWidth = blockAtlas.getWidth(0);
		atlasHeight = blockAtlas.getHeight(0);
		frameModelView.set(modelViewMatrix);
		setupCamera(levelRenderState.cameraRenderState);

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
				if (canReusePlan(dispatcher, epoch, visibleSections, trackSprites, direct, cullMask, meshActive)) {
					ChunkSectionsToRender reused = reuseRegional(blockAtlas, trackSprites, now, meshActive, cull);
					if (reused != null) {
						lastRegional = true;
						return reused;
					}
				}
				ChunkSectionsToRender regional = prepareRegional(dispatcher, epoch, visibleSections, blockAtlas, now,
					trackSprites, direct, cullMask, meshActive, cull);
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

	/** Vanilla {@code getVisibility}, without calling it once the fade has finished (where vanilla returns exactly 1). */
	private static float visibility(SectionRenderDispatcher.RenderSection section, long now) {
		if (SWAP_HOOK) {
			if (now >= ((SectionMeshSwapHook) section).ephemera$fadeEndMs()) {
				return 1.0f;
			}
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
	private boolean canReusePlan(SectionRenderDispatcher dispatcher, int epoch,
								 ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
								 boolean trackSprites, boolean direct, int cullMask, boolean meshActive) {
		if (!planValid || !TerrainFeatures.planReuse()
			|| dispatcher != planDispatcher || epoch != planEpoch
			|| planTopology != TerrainTopology.topology()
			|| hasCamera != planHasCamera || camX != planCamX || camY != planCamY || camZ != planCamZ
			|| fogCull != planFogCull || Float.compare(envEnd, planEnvEnd) != 0 || Float.compare(rdEnd, planRdEnd) != 0
			|| cullMask != planCullMask || atlasWidth != planAtlasWidth || atlasHeight != planAtlasHeight
			|| trackSprites != planTrackSprites || direct != planDirect || meshActive != planMeshActive
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

	/** Re-arms last frame's plan: fresh section uniforms (this frame's model-view + fading alpha) and sprite stamps, nothing else. */
	private @Nullable ChunkSectionsToRender reuseRegional(GpuTextureView blockAtlas, boolean trackSprites,
														  long now, boolean meshActive, boolean dirCull) {
		boolean anySettled = false;
		for (int i = 0; i < fadingCount; i++) {
			float vis = visibility(fadingSections[i], now);
			if (vis == 1.0f) {
				anySettled = true;
			}
			int ubo = fadingUbos[i];
			exactInfos[ubo] = sectionInfo(fadingCaches[i], fadingLayouts[i], fadingOrigins[i], vis);
		}
		if (anySettled) {
			// Rebuild on the next frame so newly settled sections merge into their shared region UBO.
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
		GpuBufferSlice[] slices = uboCount == 0 ? NO_SLICES : RenderSystem.getDynamicUniforms().writeChunkSections(exactInfos);
		if (!plan.reuse(blockAtlas, slices)) {
			return null;
		}
		if (trackSprites && now - lastSpriteStamp >= SPRITE_RESTAMP_MS) {
			Object[] caches = spriteSections.elements();
			for (int i = 0, n = spriteSections.size(); i < n; i++) {
				VisibleSpriteTracker.markSection((SectionMeshRenderCache) caches[i], now);
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

	private ChunkSectionsToRender prepareRegional(SectionRenderDispatcher dispatcher, int epoch,
												  ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
												  GpuTextureView blockAtlas, long now, boolean trackSprites,
												  boolean direct, int cullMask, boolean meshActive, boolean dirCull) {
		// Read the counters before looking at any section: a change racing the build then
		// only causes one extra rebuild, never a stale reuse.
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
		if (!lastRegional) {
			resetGroups();
		}
		uboCount = 0;
		plan.begin();
		regionUbos.clear();
		spriteSections.clear();
		long lastRegionKey = Long.MIN_VALUE;
		int lastRegionUbo = -1;
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
		int spriteGeneration = trackSprites ? VisibleSpriteTracker.generation().id : 0;
		dispatcher.lock();
		try {
			for (int i = 0; i < size; i++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
				if (!(section.getSectionMesh() instanceof SectionMeshRenderCache cache)) {
					continue;
				}
				int mask = cache.ephemera$readyMask(epoch);
				if (mask == 0) {
					continue;
				}
				BlockPos origin = null;
				if (doFog) {
					int ox, oy, oz;
					if (cache.ephemera$hasSectionCoords()) {
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
					VulkanMeshSectionHandle h = cache.ephemera$meshHandle();
					if (h == null || h.released || h.arenaEpoch != meshEpoch) {
						layout = cache.ephemera$layout();
						if (layout != null && layout.meshlets != null) {
							h = meshEngine.uploadSection(layout.meshlets, visibility);
							cache.ephemera$setMeshHandle(h);
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
					// Settled region-baked sections share their region's slot; fading or unbaked
					// sections get their own, positioned at the origin their vertices are relative to.
					int ubo;
					if (layout != null && layout.regionRelative && visibility == 1.0f) {
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
						} else {
							unbakedCount++;
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
						if ((custom & bit) != 0) {
							plan.addIndexed(l, ubo, vertexBuffer, state.indexBuffers[l], state.indexTypes[l],
								state.firstIndices[l], state.indexCounts[l], baseVertex);
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
								plan.addQuadSpans(l, ubo, vertexBuffer, baseVertex, state.cachedSpans, spanOff, n);
							}
						} else {
							plan.addSequential(l, ubo, vertexBuffer, baseVertex, state.indexCounts[l]);
						}
					} while (mask != 0);
				}

				if (trackSprites && cache.ephemera$needsSpriteStamp(spriteGeneration)) {
					VisibleSpriteTracker.markSection(cache, now);
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

		lastSectionCount = uboCount;
		lastFogCulled = fogCulled;
		lastUnbakedCount = unbakedCount;
		rebuiltFrames++;
		if (DIAG && now - lastDiagMs >= 2000L) {
			lastDiagMs = now;
			TerrainFeatures.LOGGER.info("TerrainDiag: visible={} fogCulled={} meshSec={} meshTasks={}/{} ubos={} batches={} cmds={} fading={} unbaked={} reused={} rebuilt={}",
				size, fogCulled, meshSectionCount, meshSolidTaskCount, meshCutoutTaskCount, uboCount, plan.lastBatchCount(), plan.lastCommandCount(), fadingCount, unbakedCount, reusedFrames, rebuiltFrames);
		}
		GpuBufferSlice[] slices = uboCount == 0 ? NO_SLICES : RenderSystem.getDynamicUniforms().writeChunkSections(exactInfos(uboCount));
		plan.finish(blockAtlas, slices, direct);
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
		planValid = true;
		return result;
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
		dispatcher.lock();
		try {
			Object[] elements = visibleSections.elements();
			int size = visibleSections.size();
			for (int i = 0; i < size; i++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
				if (!(section.getSectionMesh() instanceof SectionMeshRenderCache cache) || !cache.ephemera$hasDraws()) {
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
			// The shared matrix is written into the UBO immediately by writeChunkSections, so
			// every section of a frame can reference the same instance.
			info = new DynamicUniforms.ChunkSectionInfo(frameModelView, x, y, z, visibility, atlasWidth, atlasHeight);
			cache.ephemera$cacheInfo(info);
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
