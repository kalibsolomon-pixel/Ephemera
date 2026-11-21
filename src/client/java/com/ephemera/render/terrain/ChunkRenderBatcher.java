package com.ephemera.render.terrain;

import com.ephemera.core.terrain.FastFrustumMath;
import com.ephemera.mixin.client.terrain.TerrainFogRendererAccessor;
import com.ephemera.mixin.client.terrain.TerrainUberGpuBufferHeapAccessor;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.DynamicUniforms;
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
 * Allocation-light replacement for {@code LevelRenderer.prepareChunkRenders}.
 *
 * <p>Produces the same {@link ChunkSectionsToRender} as vanilla (same draw grouping hash,
 * same per-layer order, same section UBO contents) but:
 * <ul>
 *   <li>reuses the layer {@code EnumMap}, its three {@code Int2ObjectOpenHashMap}s and their
 *   draw lists across frames instead of rebuilding them;</li>
 *   <li>reads allocations, draws and section-info records straight from
 *   {@link SectionMeshRenderCache} fields instead of two {@code HashMap.get} calls and a
 *   {@code RenderSectionBufferSlice} allocation per layer per section;</li>
 *   <li>shares one model-view matrix per frame instead of copying it per section, reuses
 *   {@code RenderPass.Draw} records and their uniform lambdas while a section's draw state
 *   and UBO slot are unchanged, and reads the clock once per frame;</li>
 *   <li>skips sections that the terrain shader would render entirely in fog colour.</li>
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
	private static final ChunkRenderBatcher INSTANCE = new ChunkRenderBatcher();

	private final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups =
		new EnumMap<>(ChunkSectionLayer.class);
	private final Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>[] groups;
	private final Matrix4f frameModelView = new Matrix4f();
	private final int[] vertexSizes = new int[LAYER_COUNT];
	private DynamicUniforms.ChunkSectionInfo[] infos = new DynamicUniforms.ChunkSectionInfo[1024];
	private DynamicUniforms.ChunkSectionInfo[] exactInfos = new DynamicUniforms.ChunkSectionInfo[0];
	@SuppressWarnings("unchecked")
	private BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader>[] uploaders = new BiConsumer[1024];

	private int lastSectionCount;
	private int lastFogCulled;

	@SuppressWarnings("unchecked")
	private ChunkRenderBatcher() {
		groups = new Int2ObjectOpenHashMap[LAYER_COUNT];
		for (ChunkSectionLayer layer : LAYERS) {
			Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> map = new Int2ObjectOpenHashMap<>();
			groups[layer.ordinal()] = map;
			drawGroups.put(layer, map);
		}
	}

	public static ChunkRenderBatcher get() {
		return INSTANCE;
	}

	/** Sections that received a UBO slot last frame. */
	public int lastSectionCount() {
		return lastSectionCount;
	}

	/** Sections skipped by fog occlusion last frame. */
	public int lastFogCulled() {
		return lastFogCulled;
	}

	/**
	 * Builds the frame's chunk draws, or returns null to let vanilla run (feature disabled,
	 * required mixins missing, no dispatcher, or an unexpected failure).
	 */
	public static @Nullable ChunkSectionsToRender tryPrepare(@Nullable SectionRenderDispatcher dispatcher,
															 ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
															 Matrix4fc modelViewMatrix, TextureManager textureManager,
															 LevelRenderState levelRenderState) {
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
		boolean trackSprites = VisibleSpriteTracker.trackingFrame();

		GpuTextureView blockAtlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		int atlasWidth = blockAtlas.getWidth(0);
		int atlasHeight = blockAtlas.getHeight(0);
		frameModelView.set(modelViewMatrix);
		resetGroups();
		for (int l = 0; l < LAYER_COUNT; l++) {
			vertexSizes[l] = LAYERS[l].pipeline().getVertexFormatBinding(0).getVertexSize();
		}

		// Fog occlusion mirrors fog.glsl: fully fogged when the spherical distance is past the
		// environmental end or the cylindrical distance is past the render-distance end.
		boolean fogCull = false;
		float envEnd = Float.MAX_VALUE;
		float rdEnd = Float.MAX_VALUE;
		double camX = 0;
		double camY = 0;
		double camZ = 0;
		CameraRenderState camera = levelRenderState.cameraRenderState;
		if (TerrainFeatures.fogOcclusion() && camera != null && TerrainFogRendererAccessor.getEphemeraFogEnabled()) {
			FogData fog = camera.fogData;
			Vec3 pos = camera.pos;
			if (fog != null && pos != null && fog.color.w() >= 1.0f
				&& (fog.environmentalEnd < Float.MAX_VALUE || fog.renderDistanceEnd < Float.MAX_VALUE)) {
				fogCull = true;
				envEnd = fog.environmentalEnd;
				rdEnd = fog.renderDistanceEnd;
				camX = pos.x;
				camY = pos.y;
				camZ = pos.z;
			}
		}

		int sectionCount = 0;
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
				int ox = origin.getX();
				int oy = origin.getY();
				int oz = origin.getZ();
				if (fogCull && FastFrustumMath.isFullyFogged(
					ox - camX - FOG_PADDING, oy - camY - FOG_PADDING, oz - camZ - FOG_PADDING,
					ox + 16 - camX + FOG_PADDING, oy + 16 - camY + FOG_PADDING, oz + 16 - camZ + FOG_PADDING,
					envEnd, rdEnd)) {
					fogCulled++;
					continue;
				}

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

					if (uboIndex == -1) {
						uboIndex = sectionCount++;
						addSectionInfo(cache, uboIndex, ox, oy, oz, section.getVisibility(now), atlasWidth, atlasHeight);
					}

					RenderPass.Draw<GpuBufferSlice[]> d = cache.ephemera$cachedDraw(l, uboIndex, vertex, index);
					if (d == null) {
						d = buildDraw(l, draw, vertex, index, uboIndex);
						cache.ephemera$cacheDraw(l, uboIndex, vertex, index, d);
					}
					if (!customIndex && draw.indexCount() > largestIndexCount) {
						largestIndexCount = draw.indexCount();
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

		lastSectionCount = sectionCount;
		lastFogCulled = fogCulled;
		GpuBufferSlice[] chunkSectionInfos = RenderSystem.getDynamicUniforms().writeChunkSections(exactInfos(sectionCount));
		return new ChunkSectionsToRender(blockAtlas, drawGroups, largestIndexCount, chunkSectionInfos);
	}

	private RenderPass.Draw<GpuBufferSlice[]> buildDraw(int layer, SectionMesh.SectionDraw draw,
														TlsfAllocator.Allocation vertex,
														TlsfAllocator.@Nullable Allocation index, int uboIndex) {
		GpuBuffer vertexBuffer = heapBuffer(vertex);
		GpuBuffer indexBuffer = null;
		IndexType indexType = null;
		int firstIndex = 0;
		if (index != null) {
			indexBuffer = heapBuffer(index);
			indexType = draw.indexType();
			firstIndex = (int) (index.getOffsetFromHeap() / indexType.bytes);
		}
		int baseVertex = (int) (vertex.getOffsetFromHeap() / vertexSizes[layer]);
		return new RenderPass.Draw<>(0, vertexBuffer, indexBuffer, indexType, firstIndex, draw.indexCount(), baseVertex, uploader(uboIndex));
	}

	private static GpuBuffer heapBuffer(TlsfAllocator.Allocation allocation) {
		return ((TerrainUberGpuBufferHeapAccessor) (Object) allocation.getHeap()).getEphemeraGpuBuffer();
	}

	private void addSectionInfo(SectionMeshRenderCache cache, int uboIndex, int x, int y, int z,
								float visibility, int atlasWidth, int atlasHeight) {
		DynamicUniforms.ChunkSectionInfo info = cache.ephemera$cachedInfo();
		if (info == null || info.modelView() != frameModelView || info.x() != x || info.y() != y || info.z() != z
			|| Float.compare(info.visibility(), visibility) != 0
			|| info.textureAtlasWidth() != atlasWidth || info.textureAtlasHeight() != atlasHeight) {
			// The shared matrix is written into the UBO immediately by writeChunkSections, so
			// every section of a frame can reference the same instance.
			info = new DynamicUniforms.ChunkSectionInfo(frameModelView, x, y, z, visibility, atlasWidth, atlasHeight);
			cache.ephemera$cacheInfo(info);
		}
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
