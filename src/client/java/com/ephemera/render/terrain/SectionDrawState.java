package com.ephemera.render.terrain;

import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.mixin.client.terrain.TerrainUberGpuBufferHeapAccessor;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.jspecify.annotations.Nullable;

/**
 * Draw parameters of one compiled section mesh for one dispatcher epoch, recomputed by
 * {@link SectionMeshRenderCache} whenever an allocation of the mesh is mirrored (under the
 * dispatcher's copy lock) and read by the chunk batcher under the same lock. The batcher
 * reads plain fields instead of resolving {@code TlsfAllocator.Allocation}s, heaps and
 * {@code offset / vertexSize} divisions for every section layer every frame.
 *
 * <p>Layer {@code l} is drawable iff bit {@code 1 << l} of {@link #readyMask} is set: the
 * mesh has a draw for it, its vertices are uploaded and, when it has its own index buffer,
 * so are its indices. Arrays are indexed by layer ordinal and only meaningful for ready layers.
 */
public final class SectionDrawState {
	public static final int SPAN_STRIDE = 2 * QuadBuckets.MAX_SPANS;
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final int LAYER_COUNT = LAYERS.length;
	private static int @Nullable [] vertexSizes;

	public final int epoch;
	public int readyMask;
	/** Ready layers that draw with their own index buffer (sorted translucency). */
	public int customIndexMask;
	public final GpuBuffer[] vertexBuffers = new GpuBuffer[LAYER_COUNT];
	public final int[] baseVertices = new int[LAYER_COUNT];
	public final int[] indexCounts = new int[LAYER_COUNT];
	public final @Nullable GpuBuffer[] indexBuffers = new GpuBuffer[LAYER_COUNT];
	public final @Nullable IndexType[] indexTypes = new IndexType[LAYER_COUNT];
	public final int[] firstIndices = new int[LAYER_COUNT];
	/** Directional buckets covering exactly the layer's draw, or null when it cannot be bucket-culled. */
	public final @Nullable QuadBuckets[] cullBuckets = new QuadBuckets[LAYER_COUNT];
	/** Last camera position checked against this section's directional buckets (render thread only). */
	public double lastCamX = Double.NaN;
	public double lastCamY;
	public double lastCamZ;
	/** Bitmask of layers whose cached spans are verified for {@code (lastCamX, lastCamY, lastCamZ)}. */
	public int validCamLayers;
	/** Last directional visibleMask computed per layer (-1 when uncached). */
	public final int[] cachedCullMask = new int[LAYER_COUNT];
	/** Number of cached spans per layer. */
	public final int[] cachedSpanCount = new int[LAYER_COUNT];
	/** Flat {@code [startQuad, endQuad)} pairs per layer, stride {@link #SPAN_STRIDE}. */
	public final int[] cachedSpans = new int[LAYER_COUNT * SPAN_STRIDE];

	public SectionDrawState(int epoch) {
		this.epoch = epoch;
		java.util.Arrays.fill(cachedCullMask, -1);
	}

	/** Recomputes one layer from the mesh's draw, layout and current allocations. */
	public void refresh(int layer, SectionMesh.@Nullable SectionDraw draw, @Nullable SectionLayout layout,
						TlsfAllocator.@Nullable Allocation vertex, TlsfAllocator.@Nullable Allocation index) {
		int bit = 1 << layer;
		validCamLayers &= ~bit;
		cachedCullMask[layer] = -1;
		cachedSpanCount[layer] = 0;
		boolean custom = draw != null && draw.hasCustomIndexBuffer();
		if (draw == null || vertex == null || (custom && index == null)) {
			readyMask &= ~bit;
			customIndexMask &= ~bit;
			vertexBuffers[layer] = null;
			indexBuffers[layer] = null;
			indexTypes[layer] = null;
			cullBuckets[layer] = null;
			return;
		}
		vertexBuffers[layer] = heapBuffer(vertex);
		baseVertices[layer] = (int) (vertex.getOffsetFromHeap() / vertexSize(layer));
		indexCounts[layer] = draw.indexCount();
		if (custom) {
			IndexType type = draw.indexType();
			indexBuffers[layer] = heapBuffer(index);
			indexTypes[layer] = type;
			firstIndices[layer] = (int) (index.getOffsetFromHeap() / type.bytes);
			cullBuckets[layer] = null;
			customIndexMask |= bit;
		} else {
			indexBuffers[layer] = null;
			indexTypes[layer] = null;
			firstIndices[layer] = 0;
			QuadBuckets buckets = layout != null ? layout.buckets(layer) : null;
			// The draw must cover exactly the bucketed quads (6 sequential indices per quad).
			cullBuckets[layer] = buckets != null && buckets.quadCount() * 6 == draw.indexCount() ? buckets : null;
			customIndexMask &= ~bit;
		}
		readyMask |= bit;
	}

	/** Nothing is drawable (mesh closed). */
	public void clear() {
		for (int l = 0; l < LAYER_COUNT; l++) {
			refresh(l, null, null, null, null);
		}
	}

	static GpuBuffer heapBuffer(TlsfAllocator.Allocation allocation) {
		return ((TerrainUberGpuBufferHeapAccessor) (Object) allocation.getHeap()).getEphemeraGpuBuffer();
	}

	/** Vertex stride of a layer's pipeline; section pipelines are static, so this is resolved once. */
	private static int vertexSize(int layer) {
		int[] sizes = vertexSizes;
		if (sizes == null) {
			sizes = new int[LAYER_COUNT];
			for (int l = 0; l < LAYER_COUNT; l++) {
				sizes[l] = LAYERS[l].pipeline().getVertexFormatBinding(0).getVertexSize();
			}
			vertexSizes = sizes;
		}
		return sizes[layer];
	}
}
