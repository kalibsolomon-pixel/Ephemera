package com.ephemera.core.terrain;

/**
 * O(1) segregated free-list sub-allocator for a fixed-capacity GPU buffer.
 *
 * <p>Requests are rounded up to a power-of-two multiple of {@code slabSize}. Each size
 * class keeps an intrusive LIFO free list of slab-granular offsets, so allocate and free
 * are a constant number of array operations. Space never handed out is served by a bump
 * pointer. Freed blocks are not coalesced; this trades some fragmentation for strictly
 * constant-time operations, which suits terrain meshes whose sizes repeat across rebuilds.
 *
 * <p>Offsets are returned in bytes. Not thread-safe.
 */
public final class RegionSlabAllocator {
	public static final long FAILED = -1L;

	private final long capacityBytes;
	private final int slabSize;
	private final int slabShift;
	private final int classCount;
	/** Head slab index of each class's free list, or -1. */
	private final int[] freeHeads;
	/** Next pointer of every slab; only meaningful for slabs at the head of a free block. */
	private final int[] nextFree;
	/** Size class of each allocated or free block, keyed by its first slab; -1 when unused. */
	private final byte[] blockClass;
	private final boolean[] blockLive;
	private int bumpSlab;
	private final int totalSlabs;
	private long usedBytes;
	private int liveAllocations;

	public RegionSlabAllocator(long capacityBytes, int slabSize) {
		if (slabSize <= 0 || Integer.bitCount(slabSize) != 1) {
			throw new IllegalArgumentException("slabSize must be a positive power of two");
		}
		long slabs = capacityBytes / slabSize;
		if (slabs <= 0 || slabs > Integer.MAX_VALUE / 2) {
			throw new IllegalArgumentException("capacity out of range");
		}
		this.capacityBytes = slabs * slabSize;
		this.slabSize = slabSize;
		this.slabShift = Integer.numberOfTrailingZeros(slabSize);
		this.totalSlabs = (int) slabs;
		this.classCount = 32 - Integer.numberOfLeadingZeros(totalSlabs);
		this.freeHeads = new int[classCount];
		java.util.Arrays.fill(freeHeads, -1);
		this.nextFree = new int[totalSlabs];
		this.blockClass = new byte[totalSlabs];
		java.util.Arrays.fill(blockClass, (byte) -1);
		this.blockLive = new boolean[totalSlabs];
	}

	/** Size class (log2 of slab count) a request of {@code bytes} lands in, or -1 if too large. */
	public int sizeClass(long bytes) {
		if (bytes <= 0) {
			return 0;
		}
		long slabs = (bytes + slabSize - 1) >>> slabShift;
		int cls = 64 - Long.numberOfLeadingZeros(slabs - 1);
		return cls < classCount ? cls : -1;
	}

	/** Bytes actually reserved for a request of {@code bytes}. */
	public long reservedBytes(long bytes) {
		int cls = sizeClass(bytes);
		return cls < 0 ? -1L : (long) slabSize << cls;
	}

	/** Allocates at least {@code bytes}; returns the byte offset or {@link #FAILED}. */
	public long allocate(long bytes) {
		int cls = sizeClass(bytes);
		if (cls < 0) {
			return FAILED;
		}
		int slab = freeHeads[cls];
		if (slab >= 0) {
			freeHeads[cls] = nextFree[slab];
		} else {
			int count = 1 << cls;
			if (bumpSlab + count > totalSlabs) {
				return FAILED;
			}
			slab = bumpSlab;
			bumpSlab += count;
			blockClass[slab] = (byte) cls;
		}
		blockLive[slab] = true;
		usedBytes += (long) slabSize << cls;
		liveAllocations++;
		return (long) slab << slabShift;
	}

	/** Returns a block obtained from {@link #allocate}. Double frees throw. */
	public void free(long offset) {
		if (offset < 0 || offset >= capacityBytes || (offset & (slabSize - 1)) != 0) {
			throw new IllegalArgumentException("invalid offset " + offset);
		}
		int slab = (int) (offset >>> slabShift);
		if (!blockLive[slab]) {
			throw new IllegalStateException("offset " + offset + " is not allocated");
		}
		int cls = blockClass[slab];
		blockLive[slab] = false;
		nextFree[slab] = freeHeads[cls];
		freeHeads[cls] = slab;
		usedBytes -= (long) slabSize << cls;
		liveAllocations--;
	}

	/** Size in bytes reserved for the live block at {@code offset}. */
	public long blockSize(long offset) {
		int slab = (int) (offset >>> slabShift);
		if (!blockLive[slab]) {
			throw new IllegalStateException("offset " + offset + " is not allocated");
		}
		return (long) slabSize << blockClass[slab];
	}

	/** Forgets every allocation. */
	public void reset() {
		java.util.Arrays.fill(freeHeads, -1);
		java.util.Arrays.fill(blockClass, (byte) -1);
		java.util.Arrays.fill(blockLive, false);
		bumpSlab = 0;
		usedBytes = 0;
		liveAllocations = 0;
	}

	public long capacityBytes() {
		return capacityBytes;
	}

	public long usedBytes() {
		return usedBytes;
	}

	public long highWaterBytes() {
		return (long) bumpSlab << slabShift;
	}

	public int liveAllocations() {
		return liveAllocations;
	}

	public int slabSize() {
		return slabSize;
	}
}
