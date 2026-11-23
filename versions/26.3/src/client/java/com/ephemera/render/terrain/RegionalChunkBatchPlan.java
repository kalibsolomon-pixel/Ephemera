package com.ephemera.render.terrain;

import com.ephemera.core.terrain.IndirectCommandList;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;

/**
 * 26.3 multi-draw terrain batch plan built on top of native {@code MULTIDRAW_TERRAIN} pipelines
 * (vertex buffer slot 0 = section vertex heap, slot 1 = per-instance {@code ChunkSectionInfo}
 * indexed via {@code firstInstance}) and {@link VulkanMeshTerrainEngine} for opaque Vulkan layers.
 *
 * <p>Because every draw shares slot 1 and selects its region (or fading section) entry through
 * {@code firstInstance}, unordered layers (SOLID, CUTOUT, and TRANSLUCENT under OIT) collapse
 * into ONE {@code drawIndexedIndirect} per vertex heap with zero per-section UBO binds. Classic
 * sorted translucency ({@code sortTranslucent == true}) preserves exact back-to-front order while
 * merging consecutive same-buffer runs.
 *
 * <p>Executes inside 26.3's already-open {@link RenderPass} via {@code ChunkSectionsToRender.renderLayers},
 * covering opaque, classic translucent, wireframe, and all OIT stages transparently. Render thread only.
 */
public final class RegionalChunkBatchPlan {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final int TRANSLUCENT = ChunkSectionLayer.TRANSLUCENT.ordinal();
	/** Fast flat batch table for sequentially indexed vertex heaps {@code [0, FAST_HEAPS)}. */
	private static final int FAST_HEAPS = 32;
	private static final int MAX_POOLED = 1024;

	static final class Batch {
		GpuBuffer vertexBuffer;
		GpuBufferSlice vertexSlice;
		@Nullable GpuBuffer indexBuffer;
		@Nullable IndexType indexType;
		final IndirectCommandList commands = new IndirectCommandList(16);
		int firstCommand;

		void reset(GpuBuffer vertexBuffer, GpuBufferSlice vertexSlice,
				   @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
			this.vertexBuffer = vertexBuffer;
			this.vertexSlice = vertexSlice;
			this.indexBuffer = indexBuffer;
			this.indexType = indexType;
			commands.clear();
		}

		boolean sameState(GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
			return this.vertexBuffer == vertexBuffer && this.indexBuffer == indexBuffer && this.indexType == indexType;
		}
	}

	private static final class Layer {
		boolean ordered;
		final ObjectArrayList<Batch> batches = new ObjectArrayList<>();
		final ObjectArrayList<Batch> pool = new ObjectArrayList<>();
		final Batch[] fast = new Batch[FAST_HEAPS];
		int[] touched = new int[16];
		int touchedCount;
		final Long2ObjectOpenHashMap<Batch> byKey = new Long2ObjectOpenHashMap<>();
		long lastKey = -1L;
		@Nullable Batch lastBatch;
		// Ordered layers stage draws in visible order and form back-to-front runs on finish().
		final IndirectCommandList staged = new IndirectCommandList(256);
		GpuBuffer[] stagedVertex = new GpuBuffer[256];
		GpuBuffer[] stagedIndex = new GpuBuffer[256];
		IndexType[] stagedType = new IndexType[256];

		void clear() {
			for (int i = 0, n = batches.size(); i < n; i++) {
				Batch b = batches.get(i);
				b.vertexBuffer = null;
				b.vertexSlice = null;
				b.indexBuffer = null;
				if (pool.size() < MAX_POOLED) {
					pool.add(b);
				}
			}
			batches.clear();
			for (int i = 0; i < touchedCount; i++) {
				fast[touched[i]] = null;
			}
			touchedCount = 0;
			if (!byKey.isEmpty()) {
				byKey.clear();
			}
			lastKey = -1L;
			lastBatch = null;
			int n = staged.size();
			java.util.Arrays.fill(stagedVertex, 0, n, null);
			java.util.Arrays.fill(stagedIndex, 0, n, null);
			staged.clear();
		}

		Batch newBatch(GpuBuffer vertexBuffer, GpuBufferSlice vertexSlice,
					   @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
			Batch b = pool.isEmpty() ? new Batch() : pool.pop();
			b.reset(vertexBuffer, vertexSlice, indexBuffer, indexType);
			batches.add(b);
			return b;
		}

		void touch(int slot) {
			if (touchedCount == touched.length) {
				touched = java.util.Arrays.copyOf(touched, touchedCount * 2);
			}
			touched[touchedCount++] = slot;
		}
	}

	private final Layer[] layers = new Layer[LAYERS.length];
	private final IndirectBufferRing ring = new IndirectBufferRing();
	private final int[] spanScratch = new int[2 * QuadBuckets.MAX_SPANS];
	private GpuBuffer[] bufferIds = new GpuBuffer[16];
	private GpuBufferSlice[] bufferSlices = new GpuBufferSlice[16];
	private int bufferIdCount;
	private @Nullable GpuBuffer lastBuffer;
	private int lastBufferId;

	private @Nullable ChunkSectionsToRender owner;
	private @Nullable GpuBufferSlice terrainTransformUbo;
	private @Nullable GpuBufferSlice instanceSlice;
	private @Nullable GpuBuffer indirect;
	private int maxIndicesRequired;
	private int lastCommands;
	private int lastBatches;
	private boolean built;

	RegionalChunkBatchPlan() {
		for (int l = 0; l < layers.length; l++) {
			layers[l] = new Layer();
		}
	}

	/** Draw commands issued last frame. */
	public int lastCommandCount() {
		return lastCommands;
	}

	/** Batches (pass binds + draw calls) issued last frame. */
	public int lastBatchCount() {
		return lastBatches;
	}

	/** Indirect mode is always active on 26.3 native MULTIDRAW_TERRAIN. */
	public boolean lastDirect() {
		return false;
	}

	void begin(boolean sortTranslucent) {
		owner = null;
		terrainTransformUbo = null;
		instanceSlice = null;
		indirect = null;
		maxIndicesRequired = 0;
		built = false;
		for (int l = 0; l < layers.length; l++) {
			layers[l].clear();
			layers[l].ordered = (l == TRANSLUCENT) && sortTranslucent;
		}
		java.util.Arrays.fill(bufferIds, 0, bufferIdCount, null);
		java.util.Arrays.fill(bufferSlices, 0, bufferIdCount, null);
		bufferIdCount = 0;
		lastBuffer = null;
	}

	int[] spanScratch() {
		return spanScratch;
	}

	/** Sequentially indexed spans ({@code [startQuad, endQuad)} pairs), each carrying {@code firstInstance}. */
	void addQuadSpans(int layer, GpuBuffer vertexBuffer, int baseVertex, int[] spans, int spanOffset, int spanCount,
					  int firstInstance) {
		if (spanCount == 0) {
			return;
		}
		Layer target = layers[layer];
		if (target.ordered) {
			for (int i = 0; i < spanCount; i++) {
				int startQuad = spans[spanOffset + (i << 1)];
				int endQuad = spans[spanOffset + (i << 1) + 1];
				int indexCount = (endQuad - startQuad) * 6;
				if (indexCount > 0) {
					stageOrdered(target, vertexBuffer, null, null, 0, indexCount, baseVertex + (startQuad << 2), firstInstance);
					if (indexCount > maxIndicesRequired) {
						maxIndicesRequired = indexCount;
					}
				}
			}
			return;
		}
		Batch b = sequentialBatch(target, vertexBuffer);
		int largest = b.commands.addQuadSpans(baseVertex, spans, spanOffset, spanCount, firstInstance);
		if (largest > maxIndicesRequired) {
			maxIndicesRequired = largest;
		}
	}

	/** A whole sequentially indexed section layer carrying {@code firstInstance}. */
	void addSequential(int layer, GpuBuffer vertexBuffer, int baseVertex, int indexCount, int firstInstance) {
		Layer target = layers[layer];
		if (target.ordered) {
			stageOrdered(target, vertexBuffer, null, null, 0, indexCount, baseVertex, firstInstance);
		} else {
			sequentialBatch(target, vertexBuffer).commands.add(indexCount, 1, 0, baseVertex, firstInstance);
		}
		if (indexCount > maxIndicesRequired) {
			maxIndicesRequired = indexCount;
		}
	}

	/** A section layer with its own (sorted) index buffer carrying {@code firstInstance}. */
	void addIndexed(int layer, GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType,
					int firstIndex, int indexCount, int baseVertex, int firstInstance) {
		Layer target = layers[layer];
		if (!target.ordered) {
			keyedBatch(target, bufferId(vertexBuffer), vertexBuffer, indexBuffer, indexType)
				.commands.add(indexCount, 1, firstIndex, baseVertex, firstInstance);
			return;
		}
		stageOrdered(target, vertexBuffer, indexBuffer, indexType, firstIndex, indexCount, baseVertex, firstInstance);
	}

	private void stageOrdered(Layer target, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer,
							  @Nullable IndexType indexType, int firstIndex, int indexCount, int baseVertex,
							  int firstInstance) {
		int i = target.staged.size();
		if (i == target.stagedVertex.length) {
			int n = i * 2;
			target.stagedVertex = java.util.Arrays.copyOf(target.stagedVertex, n);
			target.stagedIndex = java.util.Arrays.copyOf(target.stagedIndex, n);
			target.stagedType = java.util.Arrays.copyOf(target.stagedType, n);
		}
		target.stagedVertex[i] = vertexBuffer;
		target.stagedIndex[i] = indexBuffer;
		target.stagedType[i] = indexType;
		target.staged.add(indexCount, 1, firstIndex, baseVertex, firstInstance);
	}

	private Batch sequentialBatch(Layer layer, GpuBuffer vertexBuffer) {
		int vertexId = bufferId(vertexBuffer);
		if (vertexId < FAST_HEAPS) {
			Batch b = layer.fast[vertexId];
			if (b == null) {
				b = layer.newBatch(vertexBuffer, bufferSlices[vertexId], null, null);
				layer.fast[vertexId] = b;
				layer.touch(vertexId);
			}
			return b;
		}
		return keyedBatch(layer, vertexId, vertexBuffer, null, null);
	}

	private Batch keyedBatch(Layer layer, int vertexId, GpuBuffer vertexBuffer,
							 @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
		int indexId = indexBuffer == null ? 0 : bufferId(indexBuffer) + 1;
		int typeId = indexType == null ? 0 : indexType.ordinal() + 1;
		long key = ((long) vertexId << 32) | ((long) indexId << 2) | typeId;
		if (key == layer.lastKey && layer.lastBatch != null) {
			return layer.lastBatch;
		}
		Batch b = layer.byKey.get(key);
		if (b == null) {
			b = layer.newBatch(vertexBuffer, bufferSlices[vertexId], indexBuffer, indexType);
			layer.byKey.put(key, b);
		}
		layer.lastKey = key;
		layer.lastBatch = b;
		return b;
	}

	private int bufferId(GpuBuffer buffer) {
		if (buffer == lastBuffer) {
			return lastBufferId;
		}
		GpuBuffer[] ids = bufferIds;
		int id = -1;
		for (int i = 0; i < bufferIdCount; i++) {
			if (ids[i] == buffer) {
				id = i;
				break;
			}
		}
		if (id < 0) {
			if (bufferIdCount == ids.length) {
				bufferIds = ids = java.util.Arrays.copyOf(ids, ids.length * 2);
				bufferSlices = java.util.Arrays.copyOf(bufferSlices, ids.length);
			}
			ids[bufferIdCount] = buffer;
			bufferSlices[bufferIdCount] = buffer.slice();
			id = bufferIdCount++;
		}
		lastBuffer = buffer;
		lastBufferId = id;
		return id;
	}

	/**
	 * Forms ordered translucent runs (when {@code sortTranslucent}) and lays every batch's indirect
	 * commands out contiguously into {@link IndirectBufferRing}. Must run before any render pass opens.
	 */
	void finish(GpuBufferSlice terrainTransformUbo, @Nullable GpuBufferSlice instanceSlice) {
		Layer translucent = layers[TRANSLUCENT];
		if (translucent.ordered) {
			IndirectCommandList staged = translucent.staged;
			Batch run = null;
			for (int i = staged.size() - 1; i >= 0; i--) {
				GpuBuffer vertex = translucent.stagedVertex[i];
				GpuBuffer index = translucent.stagedIndex[i];
				IndexType type = translucent.stagedType[i];
				if (run == null || !run.sameState(vertex, index, type)) {
					run = translucent.newBatch(vertex, bufferSlices[bufferId(vertex)], index, type);
				}
				run.commands.add(staged.indexCount(i), 1, staged.firstIndex(i), staged.baseVertex(i), staged.firstInstance(i));
			}
		}

		int total = 0;
		int batchCount = 0;
		for (Layer layer : layers) {
			for (int i = 0, n = layer.batches.size(); i < n; i++) {
				Batch b = layer.batches.get(i);
				b.firstCommand = total;
				total += b.commands.size();
			}
			batchCount += layer.batches.size();
		}

		this.indirect = writeIndirect(total);
		this.terrainTransformUbo = terrainTransformUbo;
		this.instanceSlice = instanceSlice;
		this.lastCommands = total;
		this.lastBatches = batchCount;
		this.built = true;
	}

	private @Nullable GpuBuffer writeIndirect(int total) {
		int bytes = total * IndirectCommandList.BYTES_PER_COMMAND;
		if (bytes == 0) {
			return null;
		}
		if (ring.mapsDirectly()) {
			GpuBuffer buffer = ring.next(bytes);
			try (GpuBufferSlice.MappedView view = buffer.map(0L, bytes, false, true)) {
				ByteBuffer out = view.data();
				int p = 0;
				for (Layer layer : layers) {
					for (int i = 0, n = layer.batches.size(); i < n; i++) {
						p = layer.batches.get(i).commands.writeTo(out, p);
					}
				}
			}
			return buffer;
		}
		ByteBuffer staging = ring.staging(bytes);
		int p = 0;
		for (Layer layer : layers) {
			for (int i = 0, n = layer.batches.size(); i < n; i++) {
				p = layer.batches.get(i).commands.writeTo(staging, p);
			}
		}
		return ring.commit(bytes);
	}

	/**
	 * Re-arms the last built plan for a new frame with fresh terrain transform UBO (and optionally
	 * an updated instance buffer when sections are fading). The indirect buffer is reused untouched.
	 */
	boolean reuse(GpuBufferSlice terrainTransformUbo, @Nullable GpuBufferSlice updatedInstanceSlice) {
		if (!built || (lastCommands > 0 && (indirect == null || indirect.isClosed()))) {
			return false;
		}
		GpuBufferSlice inst = updatedInstanceSlice != null ? updatedInstanceSlice : this.instanceSlice;
		if (lastCommands > 0 && (inst == null || inst.buffer().isClosed())) {
			return false;
		}
		this.terrainTransformUbo = terrainTransformUbo;
		this.instanceSlice = inst;
		return true;
	}

	boolean reusable() {
		return built;
	}

	int maxIndicesRequired() {
		return maxIndicesRequired;
	}

	void bind(@Nullable ChunkSectionsToRender owner) {
		this.owner = owner;
	}

	boolean isFor(ChunkSectionsToRender candidate) {
		return owner == candidate;
	}

	void release() {
		begin(true);
		ring.close();
	}

	/**
	 * Executes the requested layers inside 26.3's open {@link RenderPass}, called from
	 * {@code ChunkSectionsToRender.renderLayers}. Handles opaque Vulkan mesh shaders, indirect
	 * regional batches, wireframe override, and all OIT stages.
	 *
	 * @return false when the plan is not ready and vanilla should render instead
	 */
	public boolean renderLayers(ChunkSectionLayer[] groupLayers, GpuSampler sampler, RenderPass pass,
								GpuTextureView blockAtlas, GpuTextureView lightmap,
								@Nullable RenderPipeline multiDrawOverride) {
		GpuBufferSlice transformUbo = this.terrainTransformUbo;
		if (!built || transformUbo == null) {
			return false;
		}
		try {
			executeLayers(groupLayers, sampler, pass, blockAtlas, lightmap, transformUbo, multiDrawOverride);
		} catch (Throwable t) {
			TerrainFeatures.disableMultiDraw(t);
			owner = null;
			built = false;
		}
		return true;
	}

	private void executeLayers(ChunkSectionLayer[] groupLayers, GpuSampler sampler, RenderPass pass,
							   GpuTextureView blockAtlas, GpuTextureView lightmap,
							   GpuBufferSlice transformUbo, @Nullable RenderPipeline multiDrawOverride) {
		RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer sequentialBuffer = maxIndicesRequired == 0 ? null : sequential.getBuffer();
		IndexType sequentialType = maxIndicesRequired == 0 ? null : sequential.type();
		GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		GpuBuffer commands = this.indirect;
		GpuBufferSlice instances = this.instanceSlice;
		int maxDrawCount = RenderSystem.getDevice().getDeviceInfo().limits().maxDrawIndirectDrawCount();

		VulkanMeshTerrainEngine meshEngine = multiDrawOverride == null ? VulkanMeshTerrainEngine.get() : null;
		boolean meshDescriptorsPushed = false;
		boolean uniformsBound = false;
		boolean instanceBound = false;

		for (ChunkSectionLayer chunkLayer : groupLayers) {
			if (meshEngine != null && meshEngine.hasLayerDraw(chunkLayer)) {
				if (meshEngine.drawLayer(pass, chunkLayer, blockAtlas, sampler, lightmap, lightmapSampler, meshDescriptorsPushed)) {
					meshDescriptorsPushed = true;
					instanceBound = false;
				}
			}
			Layer layer = layers[chunkLayer.ordinal()];
			if (layer.batches.isEmpty() || instances == null) {
				continue;
			}
			meshDescriptorsPushed = false;
			if (!uniformsBound) {
				pass.setUniform("TerrainUniform", transformUbo);
				pass.setUniform("Sampler0", blockAtlas, sampler);
				pass.setUniform("Sampler2", lightmap, lightmapSampler);
				uniformsBound = true;
			}
			RenderPipeline pipeline = multiDrawOverride != null ? multiDrawOverride : chunkLayer.pipeline(true);
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			if (!instanceBound) {
				pass.setVertexBuffer(1, instances);
				instanceBound = true;
			}
			GpuBuffer boundVertex = null;
			GpuBuffer boundIndex = null;
			for (int i = 0, n = layer.batches.size(); i < n; i++) {
				Batch b = layer.batches.get(i);
				IndirectCommandList c = b.commands;
				int count = c.size();
				if (count == 0) {
					continue;
				}
				if (b.vertexBuffer != boundVertex) {
					pass.setVertexBuffer(0, b.vertexSlice);
					boundVertex = b.vertexBuffer;
				}
				GpuBuffer indexBuffer = b.indexBuffer != null ? b.indexBuffer : sequentialBuffer;
				if (indexBuffer != boundIndex) {
					pass.setIndexBuffer(indexBuffer, b.indexBuffer != null ? b.indexType : sequentialType);
					boundIndex = indexBuffer;
				}
				if (count == 1) {
					pass.drawIndexed(c.indexCount(0), 1, c.firstIndex(0), c.baseVertex(0), c.firstInstance(0));
				} else if (commands != null) {
					long offset = (long) b.firstCommand * IndirectCommandList.BYTES_PER_COMMAND;
					int remaining = count;
					while (remaining > 0) {
						int chunk = Math.min(remaining, maxDrawCount);
						long chunkBytes = (long) chunk * IndirectCommandList.BYTES_PER_COMMAND;
						pass.drawIndexedIndirect(commands.slice(offset, chunkBytes), chunk);
						offset += chunkBytes;
						remaining -= chunk;
					}
				} else {
					for (int k = 0; k < count; k++) {
						pass.drawIndexed(c.indexCount(k), 1, c.firstIndex(k), c.baseVertex(k), c.firstInstance(k));
					}
				}
			}
		}
	}
}
