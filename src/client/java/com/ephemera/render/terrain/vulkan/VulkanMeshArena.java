package com.ephemera.render.terrain.vulkan;

import com.ephemera.core.terrain.BakedSectionMeshlets;
import com.ephemera.core.terrain.MeshletBaker;
import com.ephemera.core.terrain.MeshletSlabAllocator;
import com.ephemera.render.backend.VulkanEncoderBridge;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Persistent device-local GPU memory arena for Ephemera's {@code VK_EXT_mesh_shader} terrain
 * engine. Static geometry pools ({@code quadBuf}, {@code meshletBuf}, {@code sectionMetaBuf})
 * reside in VRAM ({@code VMA_MEMORY_USAGE_GPU_ONLY}), while per-frame ring buffers
 * ({@code visibleTasksRing}, {@code cullParamsRing}, {@code meshFrameRing}) are allocated as
 * host-visible mapped ReBAR/SAM buffers ({@code VMA_MEMORY_USAGE_AUTO} +
 * {@code HOST_ACCESS_SEQUENTIAL_WRITE | MAPPED}) — allowing zero-copy direct CPU writes into
 * device-local VRAM on RX 9070 XT and modern RTX GPUs with zero {@code vkCmdUpdateBuffer} or
 * transfer-to-graphics pipeline barriers on steady frames.
 */
final class VulkanMeshArena {
	static final int SECTION_META_BYTES = 64;
	static final int CULL_PARAMS_BYTES = 256;
	static final int MESH_FRAME_BYTES = 80;
	static final int INDIRECT_CMD_BYTES = 24;
	static final int MAX_VISIBLE_MESHLETS_PER_LAYER = 131_072;

	private static final int INITIAL_QUADS = 1_048_576; // 64 MB device-local
	private static final int INITIAL_MESHLETS = 65_536; // 1 MB device-local
	private static final int INITIAL_SECTIONS = 32_768; // 2 MB device-local
	private static final int INITIAL_VISIBLE_TASKS = 32_768; // 128 KB/frame
	private static final int RING_FRAMES = 3;
	private static final int INITIAL_STAGING_BYTES = 4 * 1024 * 1024; // 4 MB/frame
	private static final int DEFER_FRAMES = 4;

	static final class GpuBuf {
		long buffer;
		long allocation;
		long byteSize;
		long mappedPtr;
	}

	private static final class DeferredFreeQueue {
		int[] slots = new int[64];
		long[] quadHandles = new long[64];
		long[] meshletHandles = new long[64];
		int count;

		void add(int slot, long quadHandle, long meshletHandle) {
			if (count == slots.length) {
				int next = count * 2;
				slots = Arrays.copyOf(slots, next);
				quadHandles = Arrays.copyOf(quadHandles, next);
				meshletHandles = Arrays.copyOf(meshletHandles, next);
			}
			slots[count] = slot;
			quadHandles[count] = quadHandle;
			meshletHandles[count] = meshletHandle;
			count++;
		}
	}

	private final long vma;
	private final boolean taskShaderMode;

	// Persistent VRAM pools shared across frames
	final GpuBuf quadBuf;
	final GpuBuf meshletBuf;
	final GpuBuf sectionMetaBuf;

	// 3-frame ring-buffered per-frame ReBAR / mapped buffers (zero vkCmdUpdateBuffer, zero copy)
	private final GpuBuf[] visibleTasksRing = new GpuBuf[RING_FRAMES];
	private final long[] ringTasksVersion = new long[RING_FRAMES];
	private final GpuBuf[] cullParamsRing = new GpuBuf[RING_FRAMES];
	private final GpuBuf[] meshFrameRing = new GpuBuf[RING_FRAMES];
	private final GpuBuf[] stagingRing = new GpuBuf[RING_FRAMES];

	// Allocated ONLY in compute-cull fallback mode (!taskShaderMode)
	private final GpuBuf[] visibleMeshletsRing;
	private final GpuBuf[] indirectCmdRing;

	private int ringSlot;
	private long stagingOffset;

	private final MeshletSlabAllocator quadAlloc = new MeshletSlabAllocator(INITIAL_QUADS);
	private final MeshletSlabAllocator meshletAlloc = new MeshletSlabAllocator(INITIAL_MESHLETS);
	private int sectionCapacity = INITIAL_SECTIONS;
	private int[] freeSlots = new int[INITIAL_SECTIONS];
	private int freeSlotCount;

	private final DeferredFreeQueue[] deferRings = new DeferredFreeQueue[DEFER_FRAMES];
	private int frameCounter;
	private int arenaEpoch = 1;
	private boolean closed;

	VulkanMeshArena(VulkanDevice device, boolean taskShaderMode) {
		this.vma = device.vma();
		this.taskShaderMode = taskShaderMode;
		for (int i = 0; i < INITIAL_SECTIONS; i++) {
			freeSlots[i] = INITIAL_SECTIONS - 1 - i;
		}
		freeSlotCount = INITIAL_SECTIONS;
		for (int i = 0; i < DEFER_FRAMES; i++) {
			deferRings[i] = new DeferredFreeQueue();
		}

		int ssboFlags = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			| VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
		quadBuf = createDeviceBuffer((long) INITIAL_QUADS * MeshletBaker.BYTES_PER_QUAD, ssboFlags);
		meshletBuf = createDeviceBuffer((long) INITIAL_MESHLETS * MeshletBaker.BYTES_PER_MESHLET, ssboFlags);
		sectionMetaBuf = createDeviceBuffer((long) INITIAL_SECTIONS * SECTION_META_BYTES, ssboFlags);

		visibleMeshletsRing = taskShaderMode ? null : new GpuBuf[RING_FRAMES];
		indirectCmdRing = taskShaderMode ? null : new GpuBuf[RING_FRAMES];

		for (int i = 0; i < RING_FRAMES; i++) {
			visibleTasksRing[i] = createHostWriteBuffer((long) INITIAL_VISIBLE_TASKS * 4L,
				VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
			cullParamsRing[i] = createHostWriteBuffer(CULL_PARAMS_BYTES,
				VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
			meshFrameRing[i] = createHostWriteBuffer(MESH_FRAME_BYTES,
				VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
			stagingRing[i] = createStagingBuffer(INITIAL_STAGING_BYTES);
			if (!taskShaderMode) {
				visibleMeshletsRing[i] = createDeviceBuffer((long) MAX_VISIBLE_MESHLETS_PER_LAYER * 2L * 4L,
					VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
				indirectCmdRing[i] = createDeviceBuffer(INDIRECT_CMD_BYTES,
					VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
			}
		}
	}

	GpuBuf visibleTasksBuf() { return visibleTasksRing[ringSlot]; }
	GpuBuf visibleMeshletsBuf() { return visibleMeshletsRing[ringSlot]; }
	GpuBuf indirectCmdBuf() { return indirectCmdRing[ringSlot]; }
	GpuBuf cullParamsBuf() { return cullParamsRing[ringSlot]; }
	GpuBuf meshFrameBuf() { return meshFrameRing[ringSlot]; }

	/** Advances the 3-frame ring and reclaims section allocations retired >= 4 frames ago. */
	void beginFrame() {
		frameCounter++;
		ringSlot = (ringSlot + 1) % RING_FRAMES;
		stagingOffset = 0L;

		DeferredFreeQueue reclaim = deferRings[frameCounter % DEFER_FRAMES];
		for (int i = 0; i < reclaim.count; i++) {
			int slot = reclaim.slots[i];
			if (slot >= 0) {
				pushFreeSlot(slot);
			}
			quadAlloc.free(reclaim.quadHandles[i]);
			meshletAlloc.free(reclaim.meshletHandles[i]);
		}
		reclaim.count = 0;
	}

	/**
	 * Uploads one baked section's quads, relocated meshlets, and 64-byte {@code SectionMeta}
	 * into the persistent device-local arena.
	 */
	VulkanMeshSectionHandle uploadSection(VulkanCommandEncoder encoder, VulkanEncoderBridge bridge,
										  BakedSectionMeshlets baked, float initialVisibility) {
		if (closed) {
			return null;
		}
		if (freeSlotCount == 0 && !growSectionMeta(encoder, bridge)) {
			return null;
		}
		long qHandle = quadAlloc.alloc(baked.quadCount);
		if (qHandle == MeshletSlabAllocator.INVALID) {
			if (!growQuadBuffer(encoder, bridge, baked.quadCount)) {
				return null;
			}
			qHandle = quadAlloc.alloc(baked.quadCount);
			if (qHandle == MeshletSlabAllocator.INVALID) {
				return null;
			}
		}
		int totalMeshlets = baked.totalMeshlets();
		long mHandle = meshletAlloc.alloc(totalMeshlets);
		if (mHandle == MeshletSlabAllocator.INVALID) {
			if (!growMeshletBuffer(encoder, bridge, totalMeshlets)) {
				quadAlloc.free(qHandle);
				return null;
			}
			mHandle = meshletAlloc.alloc(totalMeshlets);
			if (mHandle == MeshletSlabAllocator.INVALID) {
				quadAlloc.free(qHandle);
				return null;
			}
		}

		int slot = freeSlots[--freeSlotCount];
		int quadBase = MeshletSlabAllocator.offsetOf(qHandle);
		int meshletBase = MeshletSlabAllocator.offsetOf(mHandle);

		long quadBytes = (long) baked.quadWords.length * 4L;
		long meshletBytes = (long) baked.meshletWords.length * 4L;
		long totalStage = align16(quadBytes) + align16(meshletBytes) + SECTION_META_BYTES;
		long stageBase = reserveStaging(encoder, totalStage);
		if (stageBase < 0L) {
			pushFreeSlot(slot);
			quadAlloc.free(qHandle);
			meshletAlloc.free(mHandle);
			return null;
		}

		GpuBuf stage = stagingRing[ringSlot];
		long ptr = stage.mappedPtr + stageBase;

		int[] qw = baked.quadWords;
		for (int i = 0; i < qw.length; i++) {
			MemoryUtil.memPutInt(ptr + ((long) i << 2), qw[i]);
		}

		long mStageOff = stageBase + align16(quadBytes);
		long mPtr = stage.mappedPtr + mStageOff;
		int[] mw = baked.meshletWords;
		int slotShift = slot << 10;
		for (int m = 0, off = 0; m < totalMeshlets; m++, off += 4) {
			long dst = mPtr + ((long) off << 2);
			MemoryUtil.memPutInt(dst, mw[off] + quadBase);
			MemoryUtil.memPutInt(dst + 4L, mw[off + 1] | slotShift);
			MemoryUtil.memPutInt(dst + 8L, mw[off + 2]);
			MemoryUtil.memPutInt(dst + 12L, mw[off + 3]);
		}

		long sStageOff = mStageOff + align16(meshletBytes);
		long sPtr = stage.mappedPtr + sStageOff;
		MemoryUtil.memPutInt(sPtr, baked.sectionX);
		MemoryUtil.memPutInt(sPtr + 4L, baked.sectionY);
		MemoryUtil.memPutInt(sPtr + 8L, baked.sectionZ);
		MemoryUtil.memPutInt(sPtr + 12L, baked.layerMask);
		MemoryUtil.memPutFloat(sPtr + 16L, initialVisibility);
		MemoryUtil.memPutFloat(sPtr + 20L, baked.planes[0]);
		MemoryUtil.memPutFloat(sPtr + 24L, baked.planes[1]);
		MemoryUtil.memPutFloat(sPtr + 28L, baked.planes[2]);
		MemoryUtil.memPutFloat(sPtr + 32L, baked.planes[3]);
		MemoryUtil.memPutFloat(sPtr + 36L, baked.planes[4]);
		MemoryUtil.memPutFloat(sPtr + 40L, baked.planes[5]);
		MemoryUtil.memPutFloat(sPtr + 44L, 0.0f);
		MemoryUtil.memPutInt(sPtr + 48L, meshletBase);
		MemoryUtil.memPutInt(sPtr + 52L, baked.solidMeshletCount);
		MemoryUtil.memPutInt(sPtr + 56L, meshletBase + baked.solidMeshletCount);
		MemoryUtil.memPutInt(sPtr + 60L, baked.cutoutMeshletCount);

		VkCommandBuffer cmd = bridge.ephemera$commandBuffer();
		copyRegion(cmd, stage.buffer, stageBase, quadBuf.buffer, (long) quadBase * MeshletBaker.BYTES_PER_QUAD, quadBytes);
		copyRegion(cmd, stage.buffer, mStageOff, meshletBuf.buffer, (long) meshletBase * MeshletBaker.BYTES_PER_MESHLET, meshletBytes);
		copyRegion(cmd, stage.buffer, sStageOff, sectionMetaBuf.buffer, (long) slot * SECTION_META_BYTES, SECTION_META_BYTES);
		bridge.ephemera$markTransferWrite();

		return new VulkanMeshSectionHandle(
			arenaEpoch, slot, qHandle, mHandle, baked.layerMask,
			baked.solidMeshletCount, baked.cutoutMeshletCount, initialVisibility
		);
	}

	/** Updates per-section {@code visibility} floats for fading sections (4 bytes per section). */
	void uploadVisibilityUpdates(VulkanCommandEncoder encoder, VulkanEncoderBridge bridge,
								 int[] slots, float[] values, int count) {
		if (count <= 0 || closed) {
			return;
		}
		long bytes = (long) count * 4L;
		long stageBase = reserveStaging(encoder, bytes);
		if (stageBase < 0L) {
			return;
		}
		GpuBuf stage = stagingRing[ringSlot];
		long ptr = stage.mappedPtr + stageBase;
		for (int i = 0; i < count; i++) {
			MemoryUtil.memPutFloat(ptr + ((long) i << 2), values[i]);
		}
		VkCommandBuffer cmd = bridge.ephemera$commandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int off = 0;
			while (off < count) {
				int batch = Math.min(64, count - off);
				VkBufferCopy.Buffer copies = VkBufferCopy.calloc(batch, stack);
				for (int i = 0; i < batch; i++) {
					int idx = off + i;
					copies.get(i)
						.srcOffset(stageBase + ((long) idx << 2))
						.dstOffset((long) slots[idx] * SECTION_META_BYTES + 16L)
						.size(4L);
				}
				VK12.vkCmdCopyBuffer(cmd, stage.buffer, sectionMetaBuf.buffer, copies);
				off += batch;
			}
		}
		bridge.ephemera$markTransferWrite();
	}

	/**
	 * Writes this frame's compact SOLID + CUTOUT task lists (or visible section slots in fallback
	 * mode) directly into the ReBAR host-visible ring buffer — skipped completely when the ring
	 * slot already holds {@code version}! Zero {@code vkCmdCopyBuffer}, zero transfer barrier.
	 */
	void writeTaskLists(VulkanCommandEncoder encoder, int[] solidTasks, int solidCount,
						int[] cutoutTasks, int cutoutCount, long version) {
		int total = solidCount + cutoutCount;
		if (total <= 0 || closed) {
			return;
		}
		if (ringTasksVersion[ringSlot] == version) {
			return;
		}
		long bytes = (long) total * 4L;
		GpuBuf target = ensureHostWriteCapacity(encoder, visibleTasksRing, ringSlot, bytes,
			VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
		long ptr = target.mappedPtr;
		for (int i = 0; i < solidCount; i++) {
			MemoryUtil.memPutInt(ptr + ((long) i << 2), solidTasks[i]);
		}
		long cutPtr = ptr + ((long) solidCount << 2);
		for (int i = 0; i < cutoutCount; i++) {
			MemoryUtil.memPutInt(cutPtr + ((long) i << 2), cutoutTasks[i]);
		}
		Vma.vmaFlushAllocation(vma, target.allocation, 0L, bytes);
		ringTasksVersion[ringSlot] = version;
	}

	void flushAllocation(GpuBuf buf, long bytes) {
		Vma.vmaFlushAllocation(vma, buf.allocation, 0L, bytes);
	}

	int epoch() {
		return arenaEpoch;
	}

	void deferFreeSection(VulkanMeshSectionHandle handle) {
		if (closed || handle == null || handle.released || handle.arenaEpoch != arenaEpoch) {
			return;
		}
		handle.released = true;
		deferRings[frameCounter % DEFER_FRAMES].add(handle.slot, handle.quadHandle, handle.meshletHandle);
	}

	void resetAllSections() {
		arenaEpoch++;
		quadAlloc.clear();
		meshletAlloc.clear();
		freeSlotCount = sectionCapacity;
		for (int i = 0; i < sectionCapacity; i++) {
			freeSlots[i] = sectionCapacity - 1 - i;
		}
		for (DeferredFreeQueue q : deferRings) {
			q.count = 0;
		}
		Arrays.fill(ringTasksVersion, 0L);
	}

	void destroy(VulkanCommandEncoder encoder) {
		if (closed) {
			return;
		}
		closed = true;
		destroyBufDeferred(encoder, quadBuf);
		destroyBufDeferred(encoder, meshletBuf);
		destroyBufDeferred(encoder, sectionMetaBuf);
		for (int i = 0; i < RING_FRAMES; i++) {
			destroyBufDeferred(encoder, visibleTasksRing[i]);
			destroyBufDeferred(encoder, cullParamsRing[i]);
			destroyBufDeferred(encoder, meshFrameRing[i]);
			destroyBufDeferred(encoder, stagingRing[i]);
			visibleTasksRing[i] = null;
			cullParamsRing[i] = null;
			meshFrameRing[i] = null;
			stagingRing[i] = null;
			if (!taskShaderMode) {
				destroyBufDeferred(encoder, visibleMeshletsRing[i]);
				destroyBufDeferred(encoder, indirectCmdRing[i]);
				visibleMeshletsRing[i] = null;
				indirectCmdRing[i] = null;
			}
		}
	}

	private GpuBuf ensureHostWriteCapacity(VulkanCommandEncoder encoder, GpuBuf[] ring, int slot, long bytes, int usage) {
		GpuBuf target = ring[slot];
		if (bytes <= target.byteSize) {
			return target;
		}
		long newSize = Long.highestOneBit(bytes) << 1;
		GpuBuf grown = createHostWriteBuffer(newSize, usage);
		long oldBuf = target.buffer;
		long oldAlloc = target.allocation;
		ring[slot] = grown;
		encoder.queueForDestroy(() -> Vma.vmaDestroyBuffer(vma, oldBuf, oldAlloc));
		return grown;
	}

	private boolean growQuadBuffer(VulkanCommandEncoder encoder, VulkanEncoderBridge bridge, int needed) {
		int nextCap = Math.max(quadAlloc.capacity() * 2, quadAlloc.capacity() + needed * 2);
		long newBytes = (long) nextCap * MeshletBaker.BYTES_PER_QUAD;
		if (newBytes > 512L * 1024L * 1024L) {
			return false;
		}
		growDeviceBuffer(encoder, bridge, quadBuf, newBytes);
		quadAlloc.grow(nextCap);
		return true;
	}

	private boolean growMeshletBuffer(VulkanCommandEncoder encoder, VulkanEncoderBridge bridge, int needed) {
		int nextCap = Math.max(meshletAlloc.capacity() * 2, meshletAlloc.capacity() + needed * 2);
		long newBytes = (long) nextCap * MeshletBaker.BYTES_PER_MESHLET;
		if (newBytes > 64L * 1024L * 1024L) {
			return false;
		}
		growDeviceBuffer(encoder, bridge, meshletBuf, newBytes);
		meshletAlloc.grow(nextCap);
		return true;
	}

	private boolean growSectionMeta(VulkanCommandEncoder encoder, VulkanEncoderBridge bridge) {
		int oldCap = sectionCapacity;
		int nextCap = oldCap * 2;
		if (nextCap > 1_048_576) {
			return false;
		}
		growDeviceBuffer(encoder, bridge, sectionMetaBuf, (long) nextCap * SECTION_META_BYTES);
		freeSlots = Arrays.copyOf(freeSlots, nextCap);
		for (int i = oldCap; i < nextCap; i++) {
			freeSlots[freeSlotCount++] = nextCap - 1 - (i - oldCap);
		}
		sectionCapacity = nextCap;
		return true;
	}

	private void growDeviceBuffer(VulkanCommandEncoder encoder, VulkanEncoderBridge bridge, GpuBuf target, long newBytes) {
		int ssboFlags = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			| VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
		GpuBuf grown = createDeviceBuffer(newBytes, ssboFlags);
		bridge.ephemera$flushBarriers();
		copyRegion(bridge.ephemera$commandBuffer(), target.buffer, 0L, grown.buffer, 0L, target.byteSize);
		bridge.ephemera$markTransferWrite();
		long oldBuf = target.buffer;
		long oldAlloc = target.allocation;
		target.buffer = grown.buffer;
		target.allocation = grown.allocation;
		target.byteSize = grown.byteSize;
		encoder.queueForDestroy(() -> Vma.vmaDestroyBuffer(vma, oldBuf, oldAlloc));
	}

	private long reserveStaging(VulkanCommandEncoder encoder, long bytes) {
		long aligned = align16(bytes);
		GpuBuf cur = stagingRing[ringSlot];
		if (stagingOffset + aligned > cur.byteSize) {
			long newSize = Math.max(cur.byteSize * 2L, align16(stagingOffset + aligned) * 2L);
			GpuBuf bigger = createStagingBuffer(newSize);
			long oldBuf = cur.buffer;
			long oldAlloc = cur.allocation;
			stagingRing[ringSlot] = bigger;
			stagingOffset = 0L;
			encoder.queueForDestroy(() -> Vma.vmaDestroyBuffer(vma, oldBuf, oldAlloc));
		}
		long off = stagingOffset;
		stagingOffset += aligned;
		return off;
	}

	private void pushFreeSlot(int slot) {
		if (freeSlotCount == freeSlots.length) {
			freeSlots = Arrays.copyOf(freeSlots, freeSlots.length * 2);
		}
		freeSlots[freeSlotCount++] = slot;
	}

	private GpuBuf createDeviceBuffer(long byteSize, int usage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(byteSize)
				.usage(usage)
				.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
				.usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY);
			LongBuffer pBuf = stack.callocLong(1);
			PointerBuffer pAlloc = stack.callocPointer(1);
			int res = Vma.vmaCreateBuffer(vma, bufInfo, allocInfo, pBuf, pAlloc, null);
			if (res != VK12.VK_SUCCESS) {
				throw new IllegalStateException("vmaCreateBuffer (device) failed: " + res);
			}
			GpuBuf b = new GpuBuf();
			b.buffer = pBuf.get(0);
			b.allocation = pAlloc.get(0);
			b.byteSize = byteSize;
			return b;
		}
	}

	/**
	 * Allocates a persistently mapped sequential-write buffer using {@code VMA_MEMORY_USAGE_AUTO},
	 * landing directly in {@code DEVICE_LOCAL | HOST_VISIBLE} ReBAR VRAM on RX 9070 XT / RTX GPUs.
	 */
	private GpuBuf createHostWriteBuffer(long byteSize, int usage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(byteSize)
				.usage(usage)
				.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
				.usage(Vma.VMA_MEMORY_USAGE_AUTO)
				.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
					| Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
			LongBuffer pBuf = stack.callocLong(1);
			PointerBuffer pAlloc = stack.callocPointer(1);
			VmaAllocationInfo outInfo = VmaAllocationInfo.calloc(stack);
			int res = Vma.vmaCreateBuffer(vma, bufInfo, allocInfo, pBuf, pAlloc, outInfo);
			if (res != VK12.VK_SUCCESS) {
				throw new IllegalStateException("vmaCreateBuffer (hostWrite) failed: " + res);
			}
			long mapped = outInfo.pMappedData();
			if (mapped == 0L) {
				Vma.vmaDestroyBuffer(vma, pBuf.get(0), pAlloc.get(0));
				throw new IllegalStateException("vmaCreateBuffer hostWrite returned null mapped pointer");
			}
			GpuBuf b = new GpuBuf();
			b.buffer = pBuf.get(0);
			b.allocation = pAlloc.get(0);
			b.byteSize = byteSize;
			b.mappedPtr = mapped;
			return b;
		}
	}

	private GpuBuf createStagingBuffer(long byteSize) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(byteSize)
				.usage(VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
				.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
				.usage(Vma.VMA_MEMORY_USAGE_CPU_TO_GPU)
				.flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
			LongBuffer pBuf = stack.callocLong(1);
			PointerBuffer pAlloc = stack.callocPointer(1);
			VmaAllocationInfo outInfo = VmaAllocationInfo.calloc(stack);
			int res = Vma.vmaCreateBuffer(vma, bufInfo, allocInfo, pBuf, pAlloc, outInfo);
			if (res != VK12.VK_SUCCESS) {
				throw new IllegalStateException("vmaCreateBuffer (staging) failed: " + res);
			}
			long mapped = outInfo.pMappedData();
			if (mapped == 0L) {
				Vma.vmaDestroyBuffer(vma, pBuf.get(0), pAlloc.get(0));
				throw new IllegalStateException("vmaCreateBuffer staging returned null mapped pointer");
			}
			GpuBuf b = new GpuBuf();
			b.buffer = pBuf.get(0);
			b.allocation = pAlloc.get(0);
			b.byteSize = byteSize;
			b.mappedPtr = mapped;
			return b;
		}
	}

	private void destroyBufDeferred(VulkanCommandEncoder encoder, GpuBuf b) {
		if (b != null && b.buffer != 0L) {
			long buf = b.buffer;
			long alloc = b.allocation;
			b.buffer = 0L;
			b.allocation = 0L;
			b.mappedPtr = 0L;
			encoder.queueForDestroy(() -> Vma.vmaDestroyBuffer(vma, buf, alloc));
		}
	}

	private static void copyRegion(VkCommandBuffer cmd, long src, long srcOff, long dst, long dstOff, long size) {
		if (size <= 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
				.srcOffset(srcOff)
				.dstOffset(dstOff)
				.size(size);
			VK12.vkCmdCopyBuffer(cmd, src, dst, region);
		}
	}

	private static long align16(long v) {
		return (v + 15L) & ~15L;
	}
}
