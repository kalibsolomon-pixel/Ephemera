package com.ephemera.render.terrain;

import com.ephemera.core.terrain.BakedSectionMeshlets;
import com.ephemera.core.terrain.MeshletBaker;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.core.terrain.RegionKey;
import com.ephemera.core.terrain.SectionVertexBaker;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

import java.util.Map;

/**
 * Runs at the end of {@code SectionCompiler.compile} on the chunk-builder thread, after the
 * translucent layer was sorted (its {@code SortState} centroids therefore stay
 * section-local and resorting is unaffected):
 * <ul>
 *   <li>region-relative baking (when multi-draw is enabled) adds
 *   {@code sectionOrigin - regionOrigin} to every position of every layer, so all sections
 *   of a 16x8x16-section region share one {@code ChunkSection} uniform. The terrain shader's
 *   {@code Position + (ChunkPosition - CameraBlockPos)} then yields the same world position
 *   with {@code ChunkPosition = regionOrigin};</li>
 *   <li>directional bucketing (when directional culling is enabled) reorders the quads of
 *   sequentially indexed layers (solid, cutout) by facing;</li>
 *   <li>on modern Vulkan GPUs supporting {@code VK_EXT_mesh_shader}, bakes compact 16-byte
 *   vertices and 32-quad meshlets with local AABBs for GPU-driven mesh shader rendering.</li>
 * </ul>
 * All layers of a mesh share one origin because they share one {@code ChunkSection}
 * uniform, so region baking is all-or-nothing per section.
 */
public final class RegionVertexBaking {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final ThreadLocal<SectionVertexBaker> BAKERS = ThreadLocal.withInitial(SectionVertexBaker::new);
	private static final ThreadLocal<MeshletBaker> MESHLET_BAKERS = ThreadLocal.withInitial(MeshletBaker::new);

	private RegionVertexBaking() {}

	public static void bake(SectionPos sectionPos, SectionCompiler.Results results) {
		boolean region = TerrainFeatures.bakeRegionRelative();
		boolean buckets = TerrainFeatures.bakeDirectionalBuckets();
		boolean bakeMeshlets = VulkanModernCapabilities.meshShaderSupportedAndEnabled();
		if ((!region && !buckets && !bakeMeshlets) || !((Object) results instanceof CompileResultsLayout holder)) {
			return;
		}
		Map<ChunkSectionLayer, MeshData> layers = results.renderedLayers;
		if (layers.isEmpty()) {
			return;
		}
		for (MeshData mesh : layers.values()) {
			if (!isBlockFormat(mesh.drawState().format())) {
				// Unknown layout in some layer: positions cannot all be rebased.
				region = false;
			}
		}
		if (!region && !buckets && !bakeMeshlets) {
			return;
		}

		int sx = sectionPos.minBlockX();
		int sy = sectionPos.minBlockY();
		int sz = sectionPos.minBlockZ();
		float dx = region ? sx - RegionKey.originBlockX(sx) : 0f;
		float dy = region ? sy - RegionKey.originBlockY(sy) : 0f;
		float dz = region ? sz - RegionKey.originBlockZ(sz) : 0f;
		SectionVertexBaker baker = BAKERS.get();
		QuadBuckets[] layout = null;
		ByteBuffer solidVerts = null;
		int solidVertCount = 0;
		ByteBuffer cutoutVerts = null;
		int cutoutVertCount = 0;

		for (Map.Entry<ChunkSectionLayer, MeshData> e : layers.entrySet()) {
			MeshData mesh = e.getValue();
			MeshData.DrawState state = mesh.drawState();
			VertexFormat format = state.format();
			if (!isBlockFormat(format)) {
				continue;
			}
			boolean sequentialQuads = state.primitiveTopology() == PrimitiveTopology.QUADS && mesh.indexBuffer() == null;
			boolean reorder = buckets && sequentialQuads;
			QuadBuckets b;
			try {
				b = baker.bake(mesh.vertexBuffer(), state.vertexCount(), format.getVertexSize(), dx, dy, dz, reorder);
			} catch (Throwable t) {
				// The baker writes back only on success, so the layer is still untouched here.
				TerrainFeatures.disableBaking(t);
				b = null;
				if (region) {
					baker.bake(mesh.vertexBuffer(), state.vertexCount(), format.getVertexSize(), dx, dy, dz, false);
				}
			}
			if (b != null) {
				if (layout == null) {
					layout = new QuadBuckets[LAYERS.length];
				}
				layout[e.getKey().ordinal()] = b;
			}
			if (bakeMeshlets && sequentialQuads && format.getVertexSize() == MeshletBaker.BLOCK_STRIDE) {
				if (e.getKey() == ChunkSectionLayer.SOLID) {
					solidVerts = mesh.vertexBuffer();
					solidVertCount = state.vertexCount();
				} else if (e.getKey() == ChunkSectionLayer.CUTOUT) {
					cutoutVerts = mesh.vertexBuffer();
					cutoutVertCount = state.vertexCount();
				}
			}
		}

		BakedSectionMeshlets meshlets = null;
		if (bakeMeshlets && (solidVerts != null || cutoutVerts != null)) {
			try {
				QuadBuckets sb = layout != null ? layout[ChunkSectionLayer.SOLID.ordinal()] : null;
				QuadBuckets cb = layout != null ? layout[ChunkSectionLayer.CUTOUT.ordinal()] : null;
				meshlets = MESHLET_BAKERS.get().bakePooled(sx, sy, sz, dx, dy, dz,
					solidVerts, solidVertCount, sb, cutoutVerts, cutoutVertCount, cb);
			} catch (Throwable t) {
				meshlets = null;
			}
		}

		if (region || layout != null || meshlets != null) {
			holder.ephemera$setLayout(new SectionLayout(sx, sy, sz, region, layout, meshlets));
		}
	}

	private static boolean isBlockFormat(VertexFormat format) {
		// Position must be three floats at offset 0, as in DefaultVertexFormat.BLOCK.
		return format == DefaultVertexFormat.BLOCK || format.equals(DefaultVertexFormat.BLOCK);
	}
}
