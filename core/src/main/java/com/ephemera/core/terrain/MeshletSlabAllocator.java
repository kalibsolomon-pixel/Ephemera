package com.ephemera.core.terrain;

import java.util.Arrays;

/**
 * Pure-Java coalescing range allocator for persistent GPU meshlet/quad storage buffers.
 *
 * <p>Allocations are returned as packed {@code long} handles ({@code (offset << 32) | count})
 * with zero per-allocation heap objects. Free ranges are maintained in address-sorted parallel
 * primitive arrays so adjacent freed blocks coalesce immediately on {@link #free(long)}.
 */
public final class MeshletSlabAllocator {
	public static final long INVALID = -1L;

	private int capacity;
	private int used;
	private int[] freeOffset = new int[64];
	private int[] freeSize = new int[64];
	private int freeCount;

	public MeshletSlabAllocator(int initialCapacity) {
		if (initialCapacity <= 0) {
			throw new IllegalArgumentException("initialCapacity must be > 0");
		}
		this.capacity = initialCapacity;
		this.freeOffset[0] = 0;
		this.freeSize[0] = initialCapacity;
		this.freeCount = 1;
	}

	public static int offsetOf(long handle) {
		return (int) (handle >>> 32);
	}

	public static int countOf(long handle) {
		return (int) handle;
	}

	public static long pack(int offset, int count) {
		return ((long) offset << 32) | (count & 0xFFFFFFFFL);
	}

	public int capacity() {
		return capacity;
	}

	public int used() {
		return used;
	}

	public int freeBlockCount() {
		return freeCount;
	}

	/** Allocates a contiguous range of {@code count} elements, or returns {@link #INVALID} if full. */
	public long alloc(int count) {
		if (count <= 0) {
			throw new IllegalArgumentException("count must be > 0");
		}
		// Best-fit among small free lists, fast first-fit once a tight match is found.
		int best = -1;
		int bestSize = Integer.MAX_VALUE;
		for (int i = 0; i < freeCount; i++) {
			int sz = freeSize[i];
			if (sz >= count && sz < bestSize) {
				best = i;
				bestSize = sz;
				if (sz == count) {
					break;
				}
			}
		}
		if (best < 0) {
			return INVALID;
		}
		int offset = freeOffset[best];
		if (bestSize == count) {
			removeFree(best);
		} else {
			freeOffset[best] = offset + count;
			freeSize[best] = bestSize - count;
		}
		used += count;
		return pack(offset, count);
	}

	/** Releases a previously allocated handle and coalesces with adjacent free blocks. */
	public void free(long handle) {
		if (handle == INVALID) {
			return;
		}
		int offset = offsetOf(handle);
		int count = countOf(handle);
		if (count <= 0 || offset < 0 || offset + count > capacity) {
			throw new IllegalArgumentException("Invalid slab handle: offset=" + offset + ", count=" + count);
		}
		used -= count;
		insertAndCoalesce(offset, count);
	}

	/** Extends capacity to {@code newCapacity} (must be strictly larger); existing handles remain valid. */
	public void grow(int newCapacity) {
		if (newCapacity <= capacity) {
			throw new IllegalArgumentException("newCapacity must exceed " + capacity);
		}
		int added = newCapacity - capacity;
		int oldCap = capacity;
		capacity = newCapacity;
		insertAndCoalesce(oldCap, added);
	}

	/** Resets all allocations, returning the entire capacity to a single free block. */
	public void clear() {
		used = 0;
		freeOffset[0] = 0;
		freeSize[0] = capacity;
		freeCount = 1;
	}

	private void insertAndCoalesce(int offset, int count) {
		int idx = Arrays.binarySearch(freeOffset, 0, freeCount, offset);
		int pos = idx >= 0 ? idx : -idx - 1;
		boolean mergePrev = pos > 0 && freeOffset[pos - 1] + freeSize[pos - 1] == offset;
		boolean mergeNext = pos < freeCount && offset + count == freeOffset[pos];
		if (mergePrev && mergeNext) {
			freeSize[pos - 1] += count + freeSize[pos];
			removeFree(pos);
		} else if (mergePrev) {
			freeSize[pos - 1] += count;
		} else if (mergeNext) {
			freeOffset[pos] = offset;
			freeSize[pos] += count;
		} else {
			if (freeCount == freeOffset.length) {
				int nextLen = freeOffset.length * 2;
				freeOffset = Arrays.copyOf(freeOffset, nextLen);
				freeSize = Arrays.copyOf(freeSize, nextLen);
			}
			int tail = freeCount - pos;
			if (tail > 0) {
				System.arraycopy(freeOffset, pos, freeOffset, pos + 1, tail);
				System.arraycopy(freeSize, pos, freeSize, pos + 1, tail);
			}
			freeOffset[pos] = offset;
			freeSize[pos] = count;
			freeCount++;
		}
	}

	private void removeFree(int idx) {
		int tail = freeCount - idx - 1;
		if (tail > 0) {
			System.arraycopy(freeOffset, idx + 1, freeOffset, idx, tail);
			System.arraycopy(freeSize, idx + 1, freeSize, idx, tail);
		}
		freeCount--;
	}
}
