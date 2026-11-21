package com.ephemera.core.terrain;

import java.util.Arrays;

/**
 * Directional layout of one opaque section layer whose quads were reordered by
 * {@link SectionVertexBaker} into contiguous {@link DirectionalFaceMask} buckets, stored in
 * the symmetric order {@code [EAST, UP, SOUTH, UNASSIGNED, NORTH, DOWN, WEST]}.
 *
 * <p>Opposite facings sit mirrored around the always-drawn {@code UNASSIGNED} centre, so
 * the visible buckets of a camera never form more than {@link #MAX_VISIBLE_SPANS} ranges,
 * and a camera on the +X/+Y/+Z (or -X/-Y/-Z) side of a section sees one single range.
 *
 * <p>{@code offsets[s]} is the first quad of storage slot {@code s} and {@code offsets[7]}
 * the total quad count. {@code planes[b]} (facing buckets only, indexed by bucket id,
 * section-local block units) is the plane coordinate a camera must pass to see any quad of
 * the bucket: the lowest plane of a positive-facing bucket and the highest of a
 * negative-facing one. Empty buckets hold {@code +/-Infinity}, which never tests visible.
 *
 * <p>Immutable once built; shared freely between threads.
 */
public final class QuadBuckets {
	/** Spans {@link #spans} can produce for an arbitrary mask: alternating slots out of seven. */
	public static final int MAX_SPANS = 4;
	/** Spans for any mask that includes {@code UNASSIGNED} (every {@link #visibleMask} result). */
	public static final int MAX_VISIBLE_SPANS = 3;

	/** Bucket id stored in each slot. */
	private static final int[] SLOT_BUCKET = {
		DirectionalFaceMask.EAST, DirectionalFaceMask.UP, DirectionalFaceMask.SOUTH, DirectionalFaceMask.UNASSIGNED,
		DirectionalFaceMask.NORTH, DirectionalFaceMask.DOWN, DirectionalFaceMask.WEST,
	};
	/** Storage slot of each bucket id. */
	private static final int[] BUCKET_SLOT = new int[DirectionalFaceMask.BUCKET_COUNT];
	/** Bucket-id mask to storage-slot mask. */
	private static final byte[] SLOT_MASK = new byte[1 << DirectionalFaceMask.BUCKET_COUNT];

	static {
		for (int s = 0; s < SLOT_BUCKET.length; s++) {
			BUCKET_SLOT[SLOT_BUCKET[s]] = s;
		}
		for (int mask = 0; mask < SLOT_MASK.length; mask++) {
			int slots = 0;
			for (int b = 0; b < DirectionalFaceMask.BUCKET_COUNT; b++) {
				if ((mask & (1 << b)) != 0) {
					slots |= 1 << BUCKET_SLOT[b];
				}
			}
			SLOT_MASK[mask] = (byte) slots;
		}
	}

	private final int[] offsets;
	private final float[] planes;
	/** Slots without quads; they never split a span. */
	private final int emptySlots;

	QuadBuckets(int[] offsets, float[] planes) {
		this.offsets = offsets;
		this.planes = planes;
		int empty = 0;
		for (int s = 0; s < DirectionalFaceMask.BUCKET_COUNT; s++) {
			if (offsets[s] == offsets[s + 1]) {
				empty |= 1 << s;
			}
		}
		this.emptySlots = empty;
	}

	/** Storage slot of bucket {@code bucket}. */
	public static int slotOf(int bucket) {
		return BUCKET_SLOT[bucket];
	}

	/** Bucket stored in slot {@code slot}. */
	public static int bucketAt(int slot) {
		return SLOT_BUCKET[slot];
	}

	/**
	 * Builds a layout from explicit per-bucket quad counts (indexed by bucket id) and planes
	 * (used by tests and tools).
	 */
	public static QuadBuckets of(int[] bucketQuadCounts, float[] planes) {
		if (bucketQuadCounts.length != DirectionalFaceMask.BUCKET_COUNT || planes.length != DirectionalFaceMask.FACING_COUNT) {
			throw new IllegalArgumentException("need 7 counts and 6 planes");
		}
		return new QuadBuckets(layoutOffsets(bucketQuadCounts), planes.clone());
	}

	/** Slot-ordered offsets for per-bucket quad counts. */
	static int[] layoutOffsets(int[] bucketQuadCounts) {
		int[] offsets = new int[DirectionalFaceMask.BUCKET_COUNT + 1];
		for (int s = 0; s < DirectionalFaceMask.BUCKET_COUNT; s++) {
			offsets[s + 1] = offsets[s] + bucketQuadCounts[SLOT_BUCKET[s]];
		}
		return offsets;
	}

	public int quadCount() {
		return offsets[DirectionalFaceMask.BUCKET_COUNT];
	}

	/** First quad of bucket {@code bucket}. */
	public int start(int bucket) {
		return offsets[BUCKET_SLOT[bucket]];
	}

	/** End (exclusive) quad of bucket {@code bucket}. */
	public int end(int bucket) {
		return offsets[BUCKET_SLOT[bucket] + 1];
	}

	public int count(int bucket) {
		int s = BUCKET_SLOT[bucket];
		return offsets[s + 1] - offsets[s];
	}

	/** Copy of the eight quad offsets, in storage-slot order. */
	public int[] offsets() {
		return offsets.clone();
	}

	/** Copy of the six facing planes. */
	public float[] planes() {
		return planes.clone();
	}

	/**
	 * Buckets that may hold front-facing quads for a camera at the given position relative to
	 * the section origin. {@code margin} absorbs eye offsets the renderer applies after the
	 * camera position (view bobbing) and float rounding.
	 */
	public int visibleMask(double camLocalX, double camLocalY, double camLocalZ, double margin) {
		return DirectionalFaceMask.visibleMask(camLocalX, camLocalY, camLocalZ, planes, margin);
	}

	/**
	 * Coalesces the buckets in {@code mask} into the fewest contiguous quad ranges, in storage
	 * order. Empty buckets never split a range, and ranges with no quads are dropped.
	 *
	 * @param out receives {@code [startQuad, endQuad)} pairs; needs {@code 2 * MAX_SPANS} ints
	 *            ({@code 2 * MAX_VISIBLE_SPANS} when {@code mask} includes {@code UNASSIGNED})
	 * @return the number of spans written
	 */
	public int spans(int mask, int[] out) {
		return spans(mask, out, 0);
	}

	/** Writes {@code [startQuad, endQuad)} pairs into {@code out} starting at {@code outOffset}. */
	public int spans(int mask, int[] out, int outOffset) {
		int slots = SLOT_MASK[mask & DirectionalFaceMask.ALL] | emptySlots;
		int n = 0;
		int p = outOffset;
		while (slots != 0) {
			int s = Integer.numberOfTrailingZeros(slots);
			int len = Integer.numberOfTrailingZeros(~(slots >>> s));
			int e = s + len;
			int qs = offsets[s];
			int qe = offsets[e];
			if (qe > qs) {
				out[p] = qs;
				out[p + 1] = qe;
				p += 2;
				n++;
			}
			slots &= -1 << e;
		}
		return n;
	}

	/**
	 * Smallest single quad range covering every bucket in {@code mask}, packed as
	 * {@code start << 32 | end}; {@code start == end} when nothing is visible.
	 */
	public long coveringSpan(int mask) {
		int slots = SLOT_MASK[mask & DirectionalFaceMask.ALL] & ~emptySlots;
		if (slots == 0) {
			return 0L;
		}
		int first = Integer.numberOfTrailingZeros(slots);
		int last = 31 - Integer.numberOfLeadingZeros(slots);
		return ((long) offsets[first] << 32) | (offsets[last + 1] & 0xFFFFFFFFL);
	}

	@Override
	public String toString() {
		return "QuadBuckets" + Arrays.toString(offsets);
	}
}
