package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.ephemera.core.terrain.DirectionalFaceMask.*;
import static org.junit.jupiter.api.Assertions.*;

class SectionVertexBakerTest {
	/** {@code DefaultVertexFormat.BLOCK}: position 3f, color 4ub, uv0 2f, uv2 2s. */
	private static final int STRIDE = 28;

	/**
	 * Unit-cube faces in the exact vertex order of Minecraft 26.2 {@code FaceInfo} (verified
	 * with javap on {@code net.minecraft.client.renderer.FaceInfo.<clinit>}); FaceBakery emits
	 * vertices in this order, possibly rotated cyclically by UV rotation.
	 */
	private static final float[][] FACES = {
		// DOWN: (minX,minY,maxZ) (minX,minY,minZ) (maxX,minY,minZ) (maxX,minY,maxZ)
		{0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
		// UP: (minX,maxY,minZ) (minX,maxY,maxZ) (maxX,maxY,maxZ) (maxX,maxY,minZ)
		{0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
		// NORTH: (maxX,maxY,minZ) (maxX,minY,minZ) (minX,minY,minZ) (minX,maxY,minZ)
		{1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0},
		// SOUTH: (minX,maxY,maxZ) (minX,minY,maxZ) (maxX,minY,maxZ) (maxX,maxY,maxZ)
		{0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1},
		// WEST: (minX,maxY,minZ) (minX,minY,minZ) (minX,minY,maxZ) (minX,maxY,maxZ)
		{0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1},
		// EAST: (maxX,maxY,maxZ) (maxX,minY,maxZ) (maxX,minY,minZ) (maxX,maxY,minZ)
		{1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0},
	};

	@Test
	void classifiesMinecraftCubeFacesByOutwardWinding() {
		for (int dir = 0; dir < 6; dir++) {
			assertEquals(dir, SectionVertexBaker.classify(FACES[dir].clone()), "face " + dir);
		}
	}

	@Test
	void classificationIsInvariantUnderCyclicRotationAndTranslation() {
		for (int dir = 0; dir < 6; dir++) {
			for (int rot = 0; rot < 4; rot++) {
				float[] q = rotate(FACES[dir], rot);
				for (int i = 0; i < 12; i += 3) {
					q[i] += 37.25f;
					q[i + 1] -= 3.5f;
					q[i + 2] += 0.0625f;
				}
				assertEquals(dir, SectionVertexBaker.classify(q), "face " + dir + " rot " + rot);
			}
		}
	}

	@Test
	void reversedWindingFlipsTheBucket() {
		// A quad at x=1 wound inward is front-facing from -X: it must go to WEST, never EAST.
		assertEquals(WEST, SectionVertexBaker.classify(reverse(FACES[EAST])));
		assertEquals(DOWN, SectionVertexBaker.classify(reverse(FACES[UP])));
		assertEquals(SOUTH, SectionVertexBaker.classify(reverse(FACES[NORTH])));
	}

	@Test
	void diagonalDegenerateAndBowTieQuadsAreUnassigned() {
		// Cross-model (grass/flower) diagonal plane.
		float[] diagonal = {0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1};
		assertEquals(UNASSIGNED, SectionVertexBaker.classify(diagonal));
		// All four vertices identical.
		assertEquals(UNASSIGNED, SectionVertexBaker.classify(new float[12]));
		// Bow-tie in the y=0 plane: the two triangles wind in opposite directions.
		float[] bowTie = {0, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 1};
		assertEquals(UNASSIGNED, SectionVertexBaker.classify(bowTie));
		// Slightly non-planar quad (beyond epsilon) is unassigned.
		float[] tilted = FACES[UP].clone();
		tilted[4] += 0.01f;
		assertEquals(UNASSIGNED, SectionVertexBaker.classify(tilted));
	}

	@Test
	void oneDegenerateTriangleUsesTheOther() {
		// v2 == v3: second triangle (2,3,0) is degenerate, first still defines UP.
		float[] q = {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1};
		assertEquals(UP, SectionVertexBaker.classify(q));
	}

	@Test
	void reordersQuadsIntoBucketOrderAndKeepsVertexPayload() {
		// Emission order: EAST, diagonal, UP, DOWN, EAST, NORTH.
		List<float[]> quads = new ArrayList<>();
		quads.add(FACES[EAST]);
		quads.add(new float[] {0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1});
		quads.add(translate(FACES[UP], 0, 4, 0));
		quads.add(FACES[DOWN]);
		quads.add(translate(FACES[EAST], 5, 0, 0));
		quads.add(FACES[NORTH]);
		ByteBuffer buf = build(quads);

		QuadBuckets layout = new SectionVertexBaker().bake(buf, quads.size() * 4, STRIDE, 0, 0, 0, true);
		assertNotNull(layout);
		assertEquals(6, layout.quadCount());
		// Storage order EAST, UP, SOUTH, UNASSIGNED, NORTH, DOWN, WEST.
		assertArrayEquals(new int[] {0, 2, 3, 3, 4, 5, 6, 6}, layout.offsets());
		assertEquals(0, layout.start(EAST));
		assertEquals(2, layout.count(EAST));
		assertEquals(3, layout.start(UNASSIGNED));
		assertEquals(5, layout.start(DOWN));
		assertEquals(0, layout.count(WEST));

		// Expected new order: EAST(q0), EAST(q4), UP(q2), UNASSIGNED(q1), NORTH(q5), DOWN(q3).
		int[] expectedSource = {0, 4, 2, 1, 5, 3};
		for (int slot = 0; slot < 6; slot++) {
			int source = expectedSource[slot];
			float[] q = quads.get(source);
			for (int v = 0; v < 4; v++) {
				int p = (slot * 4 + v) * STRIDE;
				assertEquals(q[v * 3], buf.getFloat(p), 0f);
				assertEquals(q[v * 3 + 1], buf.getFloat(p + 4), 0f);
				assertEquals(q[v * 3 + 2], buf.getFloat(p + 8), 0f);
				// Payload tag written by build(): source quad and vertex index survive the move.
				assertEquals(source * 4 + v, buf.getInt(p + 12));
				assertEquals(source * 100 + v, buf.getInt(p + 24));
			}
		}

		float[] planes = layout.planes();
		assertEquals(0f, planes[DOWN]);
		assertEquals(5f, planes[UP]);
		assertEquals(0f, planes[NORTH]);
		assertEquals(Float.POSITIVE_INFINITY, planes[SOUTH]);
		assertEquals(Float.NEGATIVE_INFINITY, planes[WEST]);
		// Lowest +X plane is x=1 (the other EAST quad is at x=6).
		assertEquals(1f, planes[EAST]);
	}

	@Test
	void addsRegionOffsetWhileReordering() {
		List<float[]> quads = List.of(FACES[UP], FACES[DOWN]);
		ByteBuffer buf = build(quads);
		QuadBuckets layout = new SectionVertexBaker().bake(buf, 8, STRIDE, 240f, 112f, 16f, true);
		assertNotNull(layout);
		// UP is stored before DOWN; offset applied.
		assertEquals(0f + 240f, buf.getFloat(0));
		assertEquals(1f + 112f, buf.getFloat(4));
		assertEquals(0f + 16f, buf.getFloat(8));
		assertEquals(0f + 240f, buf.getFloat(4 * STRIDE));
		assertEquals(0f + 112f, buf.getFloat(4 * STRIDE + 4));
		assertEquals(1f + 16f, buf.getFloat(4 * STRIDE + 8));
		// Planes stay section-local.
		assertEquals(0f, layout.planes()[DOWN]);
		assertEquals(1f, layout.planes()[UP]);
	}

	@Test
	void offsetOnlyKeepsQuadOrder() {
		// Translucent layers: positions rebased, order untouched (sort state stays section-local).
		List<float[]> quads = List.of(FACES[EAST], FACES[DOWN], FACES[UP]);
		ByteBuffer buf = build(quads);
		assertNull(new SectionVertexBaker().bake(buf, 12, STRIDE, 16f, -0f, 32f, false));
		for (int i = 0; i < 3; i++) {
			for (int v = 0; v < 4; v++) {
				int p = (i * 4 + v) * STRIDE;
				float[] q = quads.get(i);
				assertEquals(q[v * 3] + 16f, buf.getFloat(p));
				assertEquals(q[v * 3 + 1], buf.getFloat(p + 4));
				assertEquals(q[v * 3 + 2] + 32f, buf.getFloat(p + 8));
				assertEquals(i * 4 + v, buf.getInt(p + 12));
			}
		}
	}

	@Test
	void respectsBufferPositionAndNonQuadCounts() {
		ByteBuffer raw = ByteBuffer.allocateDirect(8 + 3 * STRIDE).order(ByteOrder.nativeOrder());
		raw.position(8);
		ByteBuffer buf = raw.slice().order(ByteOrder.nativeOrder());
		buf.putFloat(0, 1f);
		raw.position(8);
		// Three vertices: no reorder possible, offset still applied.
		assertNull(new SectionVertexBaker().bake(raw, 3, STRIDE, 2f, 0f, 0f, true));
		assertEquals(3f, raw.getFloat(8));
		assertEquals(0f, raw.getFloat(0));
		// Nothing to do.
		assertNull(new SectionVertexBaker().bake(raw, 3, STRIDE, 0f, 0f, 0f, true));
		assertThrows(IllegalArgumentException.class, () -> new SectionVertexBaker().bake(raw, 4, STRIDE, 1f, 0f, 0f, false));
	}

	@Test
	void reusesScratchAcrossCalls() {
		SectionVertexBaker baker = new SectionVertexBaker();
		List<float[]> big = new ArrayList<>();
		for (int i = 0; i < 64; i++) {
			big.add(translate(FACES[i % 6], i, 0, 0));
		}
		QuadBuckets a = baker.bake(build(big), big.size() * 4, STRIDE, 0, 0, 0, true);
		QuadBuckets b = baker.bake(build(List.of(FACES[WEST])), 4, STRIDE, 0, 0, 0, true);
		assertEquals(64, a.quadCount());
		for (int bucket = 0; bucket < 6; bucket++) {
			assertTrue(a.count(bucket) >= 10);
		}
		assertEquals(1, b.quadCount());
		assertEquals(1, b.count(WEST));
	}

	private static ByteBuffer build(List<float[]> quads) {
		ByteBuffer buf = ByteBuffer.allocateDirect(quads.size() * 4 * STRIDE).order(ByteOrder.nativeOrder());
		for (int i = 0; i < quads.size(); i++) {
			float[] q = quads.get(i);
			for (int v = 0; v < 4; v++) {
				int p = (i * 4 + v) * STRIDE;
				buf.putFloat(p, q[v * 3]);
				buf.putFloat(p + 4, q[v * 3 + 1]);
				buf.putFloat(p + 8, q[v * 3 + 2]);
				buf.putInt(p + 12, i * 4 + v);
				buf.putFloat(p + 16, 0.25f);
				buf.putFloat(p + 20, 0.5f);
				buf.putInt(p + 24, i * 100 + v);
			}
		}
		return buf;
	}

	private static float[] rotate(float[] q, int by) {
		float[] out = new float[12];
		for (int v = 0; v < 4; v++) {
			System.arraycopy(q, ((v + by) & 3) * 3, out, v * 3, 3);
		}
		return out;
	}

	private static float[] reverse(float[] q) {
		float[] out = new float[12];
		for (int v = 0; v < 4; v++) {
			System.arraycopy(q, (3 - v) * 3, out, v * 3, 3);
		}
		return out;
	}

	private static float[] translate(float[] q, float x, float y, float z) {
		float[] out = q.clone();
		for (int i = 0; i < 12; i += 3) {
			out[i] += x;
			out[i + 1] += y;
			out[i + 2] += z;
		}
		return out;
	}
}
