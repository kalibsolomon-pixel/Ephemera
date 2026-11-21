package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.ephemera.core.terrain.DirectionalFaceMask.*;
import static org.junit.jupiter.api.Assertions.*;

class QuadBucketsTest {
	private static final float INF = Float.POSITIVE_INFINITY;
	private static final float[] NO_PLANES = {-INF, INF, -INF, INF, -INF, INF};

	/** Per-bucket counts {D10, U20, N30, S40, W50, E60, X5}; slot offsets 0,60,80,120,125,155,165,215. */
	private static final int[] COUNTS = {10, 20, 30, 40, 50, 60, 5};

	@Test
	void storageOrderIsSymmetricAroundUnassigned() {
		int[] expected = {EAST, UP, SOUTH, UNASSIGNED, NORTH, DOWN, WEST};
		for (int s = 0; s < BUCKET_COUNT; s++) {
			assertEquals(expected[s], QuadBuckets.bucketAt(s), "slot " + s);
			assertEquals(s, QuadBuckets.slotOf(expected[s]));
		}
		assertEquals(3, QuadBuckets.slotOf(UNASSIGNED));
		// Opposite facings mirror each other around the centre slot.
		assertEquals(6, QuadBuckets.slotOf(EAST) + QuadBuckets.slotOf(WEST));
		assertEquals(6, QuadBuckets.slotOf(UP) + QuadBuckets.slotOf(DOWN));
		assertEquals(6, QuadBuckets.slotOf(SOUTH) + QuadBuckets.slotOf(NORTH));
	}

	@Test
	void offsetsFollowStorageOrderAndAccessorsUseBucketIds() {
		QuadBuckets b = QuadBuckets.of(COUNTS, NO_PLANES);
		assertArrayEquals(new int[] {0, 60, 80, 120, 125, 155, 165, 215}, b.offsets());
		assertEquals(215, b.quadCount());
		assertEquals(0, b.start(EAST));
		assertEquals(60, b.end(EAST));
		assertEquals(120, b.start(UNASSIGNED));
		assertEquals(155, b.start(DOWN));
		assertEquals(165, b.end(DOWN));
		for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
			assertEquals(COUNTS[bucket], b.count(bucket), "bucket " + bucket);
		}
	}

	@Test
	void allVisibleIsOneSpan() {
		QuadBuckets b = QuadBuckets.of(new int[] {2, 3, 1, 4, 5, 6, 7}, NO_PLANES);
		int[] out = new int[2 * QuadBuckets.MAX_SPANS];
		assertEquals(1, b.spans(ALL, out));
		assertEquals(0, out[0]);
		assertEquals(28, out[1]);
	}

	@Test
	void alternatingSlotsGiveFourSpans() {
		QuadBuckets b = QuadBuckets.of(new int[] {1, 1, 1, 1, 1, 1, 1}, NO_PLANES);
		int[] out = new int[2 * QuadBuckets.MAX_SPANS];
		// Slots 0, 2, 4, 6: only reachable without UNASSIGNED.
		int mask = (1 << EAST) | (1 << SOUTH) | (1 << NORTH) | (1 << WEST);
		assertEquals(4, b.spans(mask, out));
		assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, out);
	}

	@Test
	void positiveAndNegativeOctantsAreOneSpan() {
		QuadBuckets b = QuadBuckets.of(COUNTS, NO_PLANES);
		int[] out = new int[8];
		// Camera above, south and east of the section.
		assertEquals(1, b.spans((1 << UP) | (1 << SOUTH) | (1 << EAST) | (1 << UNASSIGNED), out));
		assertArrayEquals(new int[] {0, 125}, java.util.Arrays.copyOf(out, 2));
		// Camera below, north and west.
		assertEquals(1, b.spans((1 << DOWN) | (1 << NORTH) | (1 << WEST) | (1 << UNASSIGNED), out));
		assertArrayEquals(new int[] {120, 215}, java.util.Arrays.copyOf(out, 2));
	}

	@Test
	void mixedOctantsCoalesce() {
		QuadBuckets b = QuadBuckets.of(COUNTS, NO_PLANES);
		int[] out = new int[8];
		// +X, +Y, -Z: slots 0, 1, 3, 4.
		assertEquals(2, b.spans((1 << EAST) | (1 << UP) | (1 << NORTH) | (1 << UNASSIGNED), out));
		assertArrayEquals(new int[] {0, 80, 120, 155}, java.util.Arrays.copyOf(out, 4));
		// +X, -Y, +Z: slots 0, 2, 3, 5 is the worst case with UNASSIGNED.
		assertEquals(3, b.spans((1 << EAST) | (1 << DOWN) | (1 << SOUTH) | (1 << UNASSIGNED), out));
		assertArrayEquals(new int[] {0, 60, 80, 125, 155, 165}, java.util.Arrays.copyOf(out, 6));
	}

	@Test
	void octantSpanCountsBeatDirectionOrder() {
		QuadBuckets b = QuadBuckets.of(new int[] {1, 1, 1, 1, 1, 1, 1}, NO_PLANES);
		int[] out = new int[8];
		int total = 0;
		for (int octant = 0; octant < 8; octant++) {
			int mask = 1 << UNASSIGNED;
			mask |= 1 << ((octant & 1) != 0 ? EAST : WEST);
			mask |= 1 << ((octant & 2) != 0 ? UP : DOWN);
			mask |= 1 << ((octant & 4) != 0 ? SOUTH : NORTH);
			int n = b.spans(mask, out);
			assertTrue(n >= 1 && n <= QuadBuckets.MAX_VISIBLE_SPANS, "octant " + octant);
			total += n;
		}
		// Direction-ordinal storage needed 24 spans over the eight octants; the symmetric order needs 16.
		assertEquals(16, total);
	}

	@Test
	void spansMatchBruteForceForEveryMask() {
		java.util.Random random = new java.util.Random(1234);
		int[] out = new int[2 * QuadBuckets.MAX_SPANS];
		for (int trial = 0; trial < 200; trial++) {
			int[] counts = new int[BUCKET_COUNT];
			for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
				counts[bucket] = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(5);
			}
			QuadBuckets b = QuadBuckets.of(counts, NO_PLANES);
			int quads = b.quadCount();
			for (int mask = 0; mask <= ALL; mask++) {
				boolean[] visible = new boolean[quads];
				for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
					if (contains(mask, bucket)) {
						for (int q = b.start(bucket); q < b.end(bucket); q++) {
							visible[q] = true;
						}
					}
				}
				int runs = 0;
				for (int q = 0; q < quads; q++) {
					if (visible[q] && (q == 0 || !visible[q - 1])) {
						runs++;
					}
				}
				int n = b.spans(mask, out);
				assertEquals(runs, n, "fewest spans, mask " + mask);
				boolean[] covered = new boolean[quads];
				for (int i = 0; i < n; i++) {
					assertTrue(out[2 * i] < out[2 * i + 1]);
					if (i > 0) {
						assertTrue(out[2 * i] > out[2 * i - 1], "spans ascend and never touch");
					}
					for (int q = out[2 * i]; q < out[2 * i + 1]; q++) {
						covered[q] = true;
					}
				}
				assertArrayEquals(visible, covered, "mask " + mask);
				if (contains(mask, UNASSIGNED)) {
					assertTrue(n <= QuadBuckets.MAX_VISIBLE_SPANS);
				}

				long span = b.coveringSpan(mask);
				int first = -1;
				int last = -1;
				for (int q = 0; q < quads; q++) {
					if (visible[q]) {
						first = first < 0 ? q : first;
						last = q;
					}
				}
				if (first < 0) {
					assertEquals((int) (span >>> 32), (int) span);
				} else {
					assertEquals(first, (int) (span >>> 32));
					assertEquals(last + 1, (int) span);
				}
			}
		}
	}

	@Test
	void emptyBucketsNeverSplitSpans() {
		// Counts {D4, U0, N3, S2, W5, E6, X1}: slots E[0,6) U[6,6) S[6,8) X[8,9) N[9,12) D[12,16) W[16,21).
		QuadBuckets b = QuadBuckets.of(new int[] {4, 0, 3, 2, 5, 6, 1}, NO_PLANES);
		int[] out = new int[8];
		int mask = (1 << EAST) | (1 << SOUTH) | (1 << DOWN) | (1 << UNASSIGNED);
		// UP is hidden but empty, so EAST..UNASSIGNED merge; NORTH still splits off DOWN.
		assertEquals(2, b.spans(mask, out));
		assertArrayEquals(new int[] {0, 9, 12, 16}, java.util.Arrays.copyOf(out, 4));
		QuadBuckets noNorth = QuadBuckets.of(new int[] {4, 0, 0, 2, 5, 6, 1}, NO_PLANES);
		assertEquals(1, noNorth.spans(mask, out));
		assertArrayEquals(new int[] {0, 13}, java.util.Arrays.copyOf(out, 2));
	}

	@Test
	void nothingVisibleOrEmptyGivesNoSpans() {
		QuadBuckets b = QuadBuckets.of(new int[] {4, 0, 0, 0, 0, 0, 0}, NO_PLANES);
		int[] out = new int[8];
		assertEquals(0, b.spans(1 << UP, out));
		QuadBuckets empty = QuadBuckets.of(new int[7], NO_PLANES);
		assertEquals(0, empty.spans(ALL, out));
	}

	@Test
	void coveringSpanSpansFirstToLastVisibleSlot() {
		QuadBuckets b = QuadBuckets.of(COUNTS, NO_PLANES);
		long span = b.coveringSpan((1 << UP) | (1 << SOUTH) | (1 << WEST));
		assertEquals(60, (int) (span >>> 32));
		assertEquals(215, (int) span);
		assertEquals(0L, b.coveringSpan(0));
		QuadBuckets sparse = QuadBuckets.of(new int[] {0, 0, 3, 0, 0, 0, 0}, NO_PLANES);
		assertEquals(0L, sparse.coveringSpan(1 << UP));
	}

	@Test
	void indirectCommandsForSpans() {
		QuadBuckets b = QuadBuckets.of(COUNTS, NO_PLANES);
		int[] spans = new int[8];
		int n = b.spans((1 << EAST) | (1 << DOWN) | (1 << SOUTH) | (1 << UNASSIGNED), spans);
		IndirectCommandList cmds = new IndirectCommandList(1);
		int largest = cmds.addQuadSpans(4096, spans, n);
		assertEquals(3, cmds.size());
		// EAST: quads [0, 60)
		assertEquals(60 * 6, cmds.indexCount(0));
		assertEquals(1, cmds.instanceCount(0));
		assertEquals(0, cmds.firstIndex(0));
		assertEquals(4096, cmds.baseVertex(0));
		assertEquals(0, cmds.firstInstance(0));
		// SOUTH + UNASSIGNED: quads [80, 125)
		assertEquals(45 * 6, cmds.indexCount(1));
		assertEquals(4096 + 80 * 4, cmds.baseVertex(1));
		// DOWN: quads [155, 165)
		assertEquals(10 * 6, cmds.indexCount(2));
		assertEquals(4096 + 155 * 4, cmds.baseVertex(2));
		assertEquals(60 * 6, largest);
	}

	@Test
	void commandListWritesTightNativeOrderStructs() {
		IndirectCommandList cmds = new IndirectCommandList(1);
		cmds.add(6, 1, 0, 100, 0);
		cmds.add(12, 1, 7, -3, 0);
		IndirectCommandList more = new IndirectCommandList(1);
		more.add(18, 1, 2, 9, 0);
		cmds.addAll(more);
		assertEquals(3, cmds.size());
		ByteBuffer out = ByteBuffer.allocateDirect(4 + 3 * IndirectCommandList.BYTES_PER_COMMAND).order(ByteOrder.nativeOrder());
		out.position(4);
		cmds.writeTo(out);
		assertEquals(4 + 60, out.position());
		int[] expected = {6, 1, 0, 100, 0, 12, 1, 7, -3, 0, 18, 1, 2, 9, 0};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.getInt(4 + i * 4), "int " + i);
		}
		assertEquals(20, IndirectCommandList.BYTES_PER_COMMAND);
		cmds.clear();
		assertEquals(0, cmds.size());
		// Absolute writes ignore the buffer's byte order and position.
		ByteBuffer big = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		IndirectCommandList one = new IndirectCommandList(1);
		one.add(36, 1, 5, 77, 0);
		assertEquals(8 + 20, one.writeTo(big, 8));
		assertEquals(0, big.position());
		ByteBuffer nativeView = big.duplicate().order(ByteOrder.nativeOrder());
		assertEquals(36, nativeView.getInt(8));
		assertEquals(5, nativeView.getInt(16));
		assertEquals(77, nativeView.getInt(20));
		assertThrows(IllegalArgumentException.class, () -> one.writeTo(big, 50));
		ByteBuffer small = ByteBuffer.allocateDirect(10);
		cmds.add(1, 1, 0, 0, 0);
		assertThrows(IllegalArgumentException.class, () -> cmds.writeTo(small));
	}

	@Test
	void visibleMaskUsesSectionLocalCamera() {
		// Faces at every height: highest -Y/-Z/-X plane 16, lowest +Y/+Z/+X plane 0.
		float[] planes = {16f, 0f, 16f, 0f, 16f, 0f};
		QuadBuckets b = QuadBuckets.of(new int[] {1, 1, 1, 1, 1, 1, 1}, planes);
		int mask = b.visibleMask(8, 20, 8, 0.5);
		assertTrue(contains(mask, UP));
		assertFalse(contains(mask, DOWN));
		// Inside the slab on X and Z: both sides of those axes visible.
		assertTrue(contains(mask, WEST) && contains(mask, EAST));
		assertTrue(contains(mask, NORTH) && contains(mask, SOUTH));
		assertTrue(contains(b.visibleMask(8, 15.8, 8, 0.5), UP));
		assertTrue(contains(b.visibleMask(8, 15.8, 8, 0.5), DOWN));
	}

	@Test
	void spansAndAddQuadSpansRespectNonZeroOffset() {
		QuadBuckets b = QuadBuckets.of(COUNTS, NO_PLANES);
		int[] flat = new int[24];
		java.util.Arrays.fill(flat, -99);
		int off = 8;
		int n = b.spans((1 << EAST) | (1 << DOWN) | (1 << SOUTH) | (1 << UNASSIGNED), flat, off);
		assertEquals(3, n);
		assertEquals(-99, flat[off - 1]);
		assertEquals(-99, flat[off + 6]);
		IndirectCommandList cmds = new IndirectCommandList(1);
		int largest = cmds.addQuadSpans(4096, flat, off, n);
		assertEquals(3, cmds.size());
		assertEquals(60 * 6, cmds.indexCount(0));
		assertEquals(4096, cmds.baseVertex(0));
		assertEquals(45 * 6, cmds.indexCount(1));
		assertEquals(4096 + 80 * 4, cmds.baseVertex(1));
		assertEquals(10 * 6, cmds.indexCount(2));
		assertEquals(4096 + 155 * 4, cmds.baseVertex(2));
		assertEquals(60 * 6, largest);
	}

	@Test
	void stagingBufferLimitMustBeClearedBeforeGrowingWrite() {
		ByteBuffer staging = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
		IndirectCommandList small = new IndirectCommandList(2);
		small.add(6, 1, 0, 0, 0);
		small.add(12, 1, 0, 4, 0);
		int smallBytes = small.size() * IndirectCommandList.BYTES_PER_COMMAND;
		staging.clear().limit(smallBytes);
		assertEquals(smallBytes, small.writeTo(staging, 0));

		// Next frame needs more bytes than the previous commit's limit: clear().limit(bigBytes) must succeed.
		IndirectCommandList big = new IndirectCommandList(4);
		big.add(6, 1, 0, 0, 0);
		big.add(12, 1, 0, 4, 0);
		big.add(18, 1, 0, 8, 0);
		int bigBytes = big.size() * IndirectCommandList.BYTES_PER_COMMAND;
		assertThrows(IllegalArgumentException.class, () -> big.writeTo(staging, 0));
		staging.clear().limit(bigBytes);
		assertEquals(bigBytes, big.writeTo(staging, 0));
	}
}
