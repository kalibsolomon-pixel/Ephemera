package com.ephemera.render.terrain;

import com.ephemera.core.terrain.IndirectCommandList;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One frame of terrain draws as multi-draw batches. A batch is every draw of a layer that
 * shares one {@code ChunkSection} uniform, vertex heap and index buffer; it executes as a
 * single multi-draw, or a plain {@code drawIndexed} when it holds one command. Two
 * execution modes, picked per device:
 *
 * <ul>
 *   <li><b>Direct</b> ({@code multiDrawDirectSeparate}, OpenGL): {@code multiDrawIndexed}
 *   ({@code glMultiDrawElementsBaseVertex}) reads byte offsets, index counts and base
 *   vertices from three reusable off-heap arrays. Nothing is uploaded to the GPU, so there
 *   is no indirect buffer to bind, orphan or synchronise.</li>
 *   <li><b>Indirect</b> (Vulkan): commands are written straight into the
 *   {@link IndirectBufferRing} staging buffer, uploaded once, and drawn with
 *   {@code drawIndexedIndirect} ({@code vkCmdDrawIndexedIndirect}).</li>
 * </ul>
 *
 * <p>Grouping:
 * <ul>
 *   <li>Sequentially indexed layers (solid, cutout) group freely: depth testing makes their
 *   order irrelevant, and batches keep first-seen (near-to-far) order. Common batches
 *   (small uniform slot, one of the first heaps, no own index buffer) are found through a
 *   flat table instead of a hash map.</li>
 *   <li>The translucent layer keeps vanilla's exact back-to-front order (visible order
 *   reversed); only consecutive draws with identical state are merged into one run.</li>
 * </ul>
 *
 * <p>Built by {@link ChunkRenderBatcher} during {@code prepareChunkRenders}, executed by the
 * {@code ChunkSectionsToRender.renderGroup} hook. A finished plan can be re-armed for a later
 * frame with {@link #reuse} when the batcher proves nothing it was built from changed.
 * Render thread only.
 */
public final class RegionalChunkBatchPlan {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final int TRANSLUCENT = ChunkSectionLayer.TRANSLUCENT.ordinal();
	private static final GpuBufferSlice[] NO_SLICES = new GpuBufferSlice[0];
	/** Flat batch table: uniform slots {@code [0, FAST_UBOS)} times heaps {@code [0, FAST_HEAPS)}. */
	private static final int FAST_UBOS = 1024;
	private static final int FAST_HEAP_BITS = 2;
	private static final int FAST_HEAPS = 1 << FAST_HEAP_BITS;
	/** Pooled batches kept per layer between frames (covers worlds where most sections still fade in). */
	private static final int MAX_POOLED = 4096;
	private static final int MIN_DIRECT_COMMANDS = 1024;

	static final class Batch {
		int ubo;
		GpuBuffer vertexBuffer;
		GpuBufferSlice vertexSlice;
		@Nullable GpuBuffer indexBuffer;
		@Nullable IndexType indexType;
		final IndirectCommandList commands = new IndirectCommandList(8);
		int firstCommand;

		void reset(int ubo, GpuBuffer vertexBuffer, GpuBufferSlice vertexSlice,
				   @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
			this.ubo = ubo;
			this.vertexBuffer = vertexBuffer;
			this.vertexSlice = vertexSlice;
			this.indexBuffer = indexBuffer;
			this.indexType = indexType;
			commands.clear();
		}

		boolean sameState(int ubo, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
			return this.ubo == ubo && this.vertexBuffer == vertexBuffer && this.indexBuffer == indexBuffer && this.indexType == indexType;
		}
	}

	private static final class Layer {
		final boolean ordered;
		final ObjectArrayList<Batch> batches = new ObjectArrayList<>();
		final ObjectArrayList<Batch> pool = new ObjectArrayList<>();
		/** Batches keyed by {@code (ubo << FAST_HEAP_BITS) | heapId} for common states. */
		final Batch[] fast = new Batch[FAST_UBOS * FAST_HEAPS];
		int[] touched = new int[64];
		int touchedCount;
		/** Overflow for uniform slots, heaps or index buffers outside the flat table. */
		final Long2ObjectOpenHashMap<Batch> byKey = new Long2ObjectOpenHashMap<>();
		// Consecutive visible sections mostly share a region, so the last key usually hits.
		long lastKey = -1L;
		@Nullable Batch lastBatch;
		// Ordered layers stage draws in visible order and form runs once the frame is complete.
		final IndirectCommandList staged = new IndirectCommandList(64);
		int[] stagedUbo = new int[64];
		GpuBuffer[] stagedVertex = new GpuBuffer[64];
		GpuBuffer[] stagedIndex = new GpuBuffer[64];
		IndexType[] stagedType = new IndexType[64];

		Layer(boolean ordered) {
			this.ordered = ordered;
		}

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

		Batch newBatch(int ubo, GpuBuffer vertexBuffer, GpuBufferSlice vertexSlice,
					   @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
			Batch b = pool.isEmpty() ? new Batch() : pool.pop();
			b.reset(ubo, vertexBuffer, vertexSlice, indexBuffer, indexType);
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
	/** Per-frame identity ids for heap buffers, used in batch keys. */
	private GpuBuffer[] bufferIds = new GpuBuffer[16];
	private GpuBufferSlice[] bufferSlices = new GpuBufferSlice[16];
	private int bufferIdCount;
	private @Nullable GpuBuffer lastBuffer;
	private int lastBufferId;

	// Direct mode: client-memory multi-draw parameters, valid until the next build.
	private @Nullable PointerBuffer directOffsets;
	private @Nullable IntBuffer directCounts;
	private @Nullable IntBuffer directBaseVertices;
	private boolean direct;

	private @Nullable ChunkSectionsToRender owner;
	private @Nullable GpuTextureView textureView;
	private GpuBufferSlice[] ubos = NO_SLICES;
	private @Nullable GpuBuffer indirect;
	private int maxIndicesRequired;
	private int lastCommands;
	private int lastBatches;
	private boolean built;

	RegionalChunkBatchPlan() {
		for (int l = 0; l < layers.length; l++) {
			layers[l] = new Layer(l == TRANSLUCENT);
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

	/** True when the last built plan draws with direct (client-memory) multi-draw. */
	public boolean lastDirect() {
		return direct;
	}

	void begin() {
		owner = null;
		textureView = null;
		indirect = null;
		ubos = NO_SLICES;
		maxIndicesRequired = 0;
		built = false;
		for (Layer layer : layers) {
			layer.clear();
		}
		java.util.Arrays.fill(bufferIds, 0, bufferIdCount, null);
		java.util.Arrays.fill(bufferSlices, 0, bufferIdCount, null);
		bufferIdCount = 0;
		lastBuffer = null;
	}

	int[] spanScratch() {
		return spanScratch;
	}

	/** Sequentially indexed spans ({@code [startQuad, endQuad)} pairs) of one section layer. */
	void addQuadSpans(int layer, int ubo, GpuBuffer vertexBuffer, int baseVertex, int[] spans, int spanCount) {
		addQuadSpans(layer, ubo, vertexBuffer, baseVertex, spans, 0, spanCount);
	}

	/** Sequentially indexed spans starting at {@code spanOffset} in {@code spans}. */
	void addQuadSpans(int layer, int ubo, GpuBuffer vertexBuffer, int baseVertex, int[] spans, int spanOffset, int spanCount) {
		if (spanCount == 0) {
			return;
		}
		Batch b = sequentialBatch(layers[layer], ubo, vertexBuffer);
		int largest = b.commands.addQuadSpans(baseVertex, spans, spanOffset, spanCount);
		if (largest > maxIndicesRequired) {
			maxIndicesRequired = largest;
		}
	}

	/** A whole sequentially indexed section layer. */
	void addSequential(int layer, int ubo, GpuBuffer vertexBuffer, int baseVertex, int indexCount) {
		Batch b = sequentialBatch(layers[layer], ubo, vertexBuffer);
		b.commands.add(indexCount, 1, 0, baseVertex, 0);
		if (indexCount > maxIndicesRequired) {
			maxIndicesRequired = indexCount;
		}
	}

	/** A section layer with its own (sorted) index buffer. */
	void addIndexed(int layer, int ubo, GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType,
					int firstIndex, int indexCount, int baseVertex) {
		Layer target = layers[layer];
		if (!target.ordered) {
			groupedBatch(target, ubo, vertexBuffer, indexBuffer, indexType).commands.add(indexCount, 1, firstIndex, baseVertex, 0);
			return;
		}
		int i = target.staged.size();
		if (i == target.stagedUbo.length) {
			int n = i * 2;
			target.stagedUbo = java.util.Arrays.copyOf(target.stagedUbo, n);
			target.stagedVertex = java.util.Arrays.copyOf(target.stagedVertex, n);
			target.stagedIndex = java.util.Arrays.copyOf(target.stagedIndex, n);
			target.stagedType = java.util.Arrays.copyOf(target.stagedType, n);
		}
		target.stagedUbo[i] = ubo;
		target.stagedVertex[i] = vertexBuffer;
		target.stagedIndex[i] = indexBuffer;
		target.stagedType[i] = indexType;
		target.staged.add(indexCount, 1, firstIndex, baseVertex, 0);
	}

	/** Batch for sequential-index draws: one array read for the common case, no hashing. */
	private Batch sequentialBatch(Layer layer, int ubo, GpuBuffer vertexBuffer) {
		int vertexId = bufferId(vertexBuffer);
		if (ubo < FAST_UBOS && vertexId < FAST_HEAPS) {
			int slot = (ubo << FAST_HEAP_BITS) | vertexId;
			Batch b = layer.fast[slot];
			if (b == null) {
				b = layer.newBatch(ubo, vertexBuffer, bufferSlices[vertexId], null, null);
				layer.fast[slot] = b;
				layer.touch(slot);
			}
			return b;
		}
		return keyedBatch(layer, ubo, vertexId, vertexBuffer, null, null);
	}

	private Batch groupedBatch(Layer layer, int ubo, GpuBuffer vertexBuffer,
							   @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
		if (indexBuffer == null) {
			return sequentialBatch(layer, ubo, vertexBuffer);
		}
		return keyedBatch(layer, ubo, bufferId(vertexBuffer), vertexBuffer, indexBuffer, indexType);
	}

	private Batch keyedBatch(Layer layer, int ubo, int vertexId, GpuBuffer vertexBuffer,
							 @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
		int indexId = indexBuffer == null ? 0 : bufferId(indexBuffer) + 1;
		int typeId = indexType == null ? 0 : indexType.ordinal() + 1;
		long key = ((long) ubo << 32) | ((long) vertexId << 16) | ((long) indexId << 2) | typeId;
		if (key == layer.lastKey && layer.lastBatch != null) {
			return layer.lastBatch;
		}
		Batch b = layer.byKey.get(key);
		if (b == null) {
			b = layer.newBatch(ubo, vertexBuffer, bufferSlices[vertexId], indexBuffer, indexType);
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
	 * Forms translucent runs and lays every batch's commands out contiguously, either into
	 * the direct multi-draw arrays or into the indirect staging buffer (then uploaded).
	 * Must run before any render pass of the frame opens.
	 */
	void finish(GpuTextureView textureView, GpuBufferSlice[] ubos, boolean direct) {
		Layer translucent = layers[TRANSLUCENT];
		IndirectCommandList staged = translucent.staged;
		Batch run = null;
		for (int i = staged.size() - 1; i >= 0; i--) {
			int ubo = translucent.stagedUbo[i];
			GpuBuffer vertex = translucent.stagedVertex[i];
			GpuBuffer index = translucent.stagedIndex[i];
			IndexType type = translucent.stagedType[i];
			if (run == null || !run.sameState(ubo, vertex, index, type)) {
				run = translucent.newBatch(ubo, vertex, bufferSlices[bufferId(vertex)], index, type);
			}
			run.commands.add(staged.indexCount(i), 1, staged.firstIndex(i), staged.baseVertex(i), 0);
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

		this.direct = direct;
		if (direct) {
			writeDirect(total);
			this.indirect = null;
		} else {
			this.indirect = writeIndirect(total);
		}
		this.textureView = textureView;
		this.ubos = ubos;
		this.lastCommands = total;
		this.lastBatches = batchCount;
		this.built = true;
	}

	/** {@code (firstIndex * indexBytes, indexCount, baseVertex)} per command into the client-memory arrays. */
	private void writeDirect(int total) {
		ensureDirectCapacity(total);
		PointerBuffer offsets = directOffsets;
		IntBuffer counts = directCounts;
		IntBuffer bases = directBaseVertices;
		int k = 0;
		for (Layer layer : layers) {
			for (int i = 0, n = layer.batches.size(); i < n; i++) {
				Batch b = layer.batches.get(i);
				IndirectCommandList c = b.commands;
				// Sequential draws always start at index 0, whatever the shared index buffer's type.
				long indexBytes = b.indexType != null ? b.indexType.bytes : 0L;
				for (int j = 0, m = c.size(); j < m; j++, k++) {
					offsets.put(k, c.firstIndex(j) * indexBytes);
					counts.put(k, c.indexCount(j));
					bases.put(k, c.baseVertex(j));
				}
			}
		}
	}

	private void ensureDirectCapacity(int commands) {
		PointerBuffer offsets = directOffsets;
		if (offsets != null && offsets.capacity() >= commands) {
			return;
		}
		int capacity = Math.max(MIN_DIRECT_COMMANDS, Integer.highestOneBit(Math.max(1, commands - 1)) << 1);
		freeDirect();
		directOffsets = MemoryUtil.memAllocPointer(capacity);
		directCounts = MemoryUtil.memAllocInt(capacity);
		directBaseVertices = MemoryUtil.memAllocInt(capacity);
	}

	private void freeDirect() {
		if (directOffsets != null) {
			MemoryUtil.memFree(directOffsets);
			directOffsets = null;
		}
		if (directCounts != null) {
			MemoryUtil.memFree(directCounts);
			directCounts = null;
		}
		if (directBaseVertices != null) {
			MemoryUtil.memFree(directBaseVertices);
			directBaseVertices = null;
		}
	}

	/** Every batch's commands straight into the ring's staging buffer, then one upload. */
	private @Nullable GpuBuffer writeIndirect(int total) {
		int bytes = total * IndirectCommandList.BYTES_PER_COMMAND;
		if (bytes == 0) {
			return null;
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
	 * Re-arms the last built plan for a new frame with that frame's section uniforms. The
	 * direct arrays or uploaded indirect buffer are reused untouched.
	 *
	 * @return false when there is no plan to reuse (the caller must rebuild)
	 */
	boolean reuse(GpuTextureView textureView, GpuBufferSlice[] ubos) {
		if (!built || (!direct && lastCommands > 0 && (indirect == null || indirect.isClosed()))) {
			return false;
		}
		this.textureView = textureView;
		this.ubos = ubos;
		return true;
	}

	/** Whether the plan holds a complete frame that {@link #reuse} could re-arm. */
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

	/** Drops the frame and releases GPU buffers and client-memory draw arrays (dispatcher disposal). */
	void release() {
		begin();
		ring.close();
		freeDirect();
		direct = false;
	}

	/**
	 * Mirrors vanilla {@code ChunkSectionsToRender.renderGroup} (same render pass, default
	 * uniforms, samplers, wireframe switch and layer loop) with batched multi-draws.
	 *
	 * @return false when the plan could not run and vanilla should render instead
	 */
	public boolean render(ChunkSectionLayerGroup group, GpuSampler sampler) {
		GpuTextureView atlas = textureView;
		if (atlas == null) {
			return false;
		}
		try {
			renderPass(group, sampler, atlas);
		} catch (Throwable t) {
			TerrainFeatures.disableMultiDraw(t);
			owner = null;
			built = false;
		}
		return true;
	}

	private void renderPass(ChunkSectionLayerGroup group, GpuSampler sampler, GpuTextureView atlas) {
		RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer sequentialBuffer = maxIndicesRequired == 0 ? null : sequential.getBuffer(maxIndicesRequired);
		IndexType sequentialType = maxIndicesRequired == 0 ? null : sequential.type();
		ChunkSectionLayer[] groupLayers = group.layers();
		Minecraft minecraft = Minecraft.getInstance();
		boolean wireframe = SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe;
		RenderTarget target = group.outputTarget();
		GpuBuffer commands = indirect;
		GpuBufferSlice[] sectionUbos = ubos;
		boolean directDraw = direct;
		PointerBuffer offsets = directOffsets;
		IntBuffer counts = directCounts;
		IntBuffer bases = directBaseVertices;

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "Section layers for " + group.label(),
			target.getColorTextureView(), Optional.empty(),
			target.getDepthTextureView(), OptionalDouble.empty())) {
			GpuTextureView lightmap = minecraft.gameRenderer.lightmap();
			GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("Sampler0", atlas, sampler);
			pass.bindTexture("Sampler2", lightmap, lightmapSampler);
			VulkanMeshTerrainEngine meshEngine = wireframe ? null : VulkanMeshTerrainEngine.get();
			boolean meshDescriptorsPushed = false;

			for (ChunkSectionLayer chunkLayer : groupLayers) {
				if (meshEngine != null && meshEngine.hasLayerDraw(chunkLayer)) {
					if (meshEngine.drawLayer(pass, chunkLayer, target, atlas, sampler, lightmap, lightmapSampler, meshDescriptorsPushed)) {
						meshDescriptorsPushed = true;
					}
				}
				Layer layer = layers[chunkLayer.ordinal()];
				if (layer.batches.isEmpty()) {
					continue;
				}
				meshDescriptorsPushed = false;
				pass.setPipeline(wireframe ? RenderPipelines.WIREFRAME : chunkLayer.pipeline());
				int boundUbo = -1;
				GpuBuffer boundVertex = null;
				GpuBuffer boundIndex = null;
				for (int i = 0, n = layer.batches.size(); i < n; i++) {
					Batch b = layer.batches.get(i);
					IndirectCommandList c = b.commands;
					int count = c.size();
					if (count == 0) {
						continue;
					}
					GpuBuffer indexBuffer = b.indexBuffer != null ? b.indexBuffer : sequentialBuffer;
					if (indexBuffer != boundIndex) {
						pass.setIndexBuffer(indexBuffer, b.indexBuffer != null ? b.indexType : sequentialType);
						boundIndex = indexBuffer;
					}
					if (b.ubo != boundUbo) {
						pass.setUniform("ChunkSection", sectionUbos[b.ubo]);
						boundUbo = b.ubo;
					}
					if (b.vertexBuffer != boundVertex) {
						pass.setVertexBuffer(0, b.vertexSlice);
						boundVertex = b.vertexBuffer;
					}
					if (count == 1) {
						pass.drawIndexed(c.indexCount(0), 1, c.firstIndex(0), c.baseVertex(0), 0);
					} else if (directDraw) {
						int first = b.firstCommand;
						offsets.position(first);
						counts.position(first);
						bases.position(first);
						pass.multiDrawIndexed(offsets, counts, bases, count);
					} else if (commands != null) {
						pass.drawIndexedIndirect(commands.slice(
							(long) b.firstCommand * IndirectCommandList.BYTES_PER_COMMAND,
							(long) count * IndirectCommandList.BYTES_PER_COMMAND), count);
					} else {
						for (int k = 0; k < count; k++) {
							pass.drawIndexed(c.indexCount(k), 1, c.firstIndex(k), c.baseVertex(k), 0);
						}
					}
				}
			}
		}
	}
}
