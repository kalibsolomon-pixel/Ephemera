package com.ephemera.core.terrain;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Off-thread post-pass over a compiled section layer's vertex data (positions are three
 * native-order floats at the start of each vertex, as in {@code DefaultVertexFormat.BLOCK}).
 *
 * <ul>
 *   <li>Adds a constant {@code (dx, dy, dz)} to every position, rebasing section-local
 *   vertices onto their region origin. Region-relative coordinates stay within
 *   {@code [-1, 257]}, where a float still resolves 1/32768 of a block.</li>
 *   <li>Optionally classifies every quad by the facing of its triangle winding and
 *   reorders quads into contiguous {@link DirectionalFaceMask} buckets, laid out in the
 *   symmetric {@link QuadBuckets} storage order. The classification
 *   uses the same winding the GPU's back-face test uses, so a quad is only ever bucketed by
 *   the side it is actually visible from. Quads that are not exactly axis-aligned planes,
 *   are degenerate, or whose two triangles disagree land in {@code UNASSIGNED}, which is
 *   always drawn.</li>
 * </ul>
 *
 * <p>Reordering is only valid for sequentially indexed geometry (no custom index buffer).
 * One instance per thread; scratch arrays are reused and grow monotonically.
 */
public final class SectionVertexBaker {
	public static final int VERTICES_PER_QUAD = 4;
	/** Positions closer than this are treated as the same plane. */
	public static final float PLANE_EPSILON = 1.0e-5f;

	private static final VarHandle FLOAT = MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());

	private byte[] src = new byte[0];
	private byte[] dst = new byte[0];
	private byte[] quadBucket = new byte[0];
	private final int[] counts = new int[DirectionalFaceMask.BUCKET_COUNT];
	private final int[] cursor = new int[DirectionalFaceMask.BUCKET_COUNT];
	private final float[] planes = new float[DirectionalFaceMask.FACING_COUNT];
	private final float[] quad = new float[12];

	/**
	 * Rewrites {@code vertexCount * stride} bytes of {@code buffer} starting at its position.
	 *
	 * @return the bucket layout when quads were reordered, otherwise null
	 */
	public QuadBuckets bake(ByteBuffer buffer, int vertexCount, int stride, float dx, float dy, float dz, boolean reorder) {
		if (stride < 12 || vertexCount <= 0) {
			return null;
		}
		boolean offset = dx != 0f || dy != 0f || dz != 0f;
		boolean sort = reorder && vertexCount % VERTICES_PER_QUAD == 0;
		if (!offset && !sort) {
			return null;
		}
		int bytes = Math.multiplyExact(vertexCount, stride);
		int base = buffer.position();
		if (buffer.limit() - base < bytes) {
			throw new IllegalArgumentException("buffer holds " + (buffer.limit() - base) + " bytes, need " + bytes);
		}
		byte[] in = grow(src, bytes);
		src = in;
		buffer.get(base, in, 0, bytes);

		byte[] out = in;
		QuadBuckets result = null;
		if (sort) {
			out = grow(dst, bytes);
			dst = out;
			result = reorder(in, out, vertexCount / VERTICES_PER_QUAD, stride);
		}
		if (offset) {
			for (int v = 0, p = 0; v < vertexCount; v++, p += stride) {
				FLOAT.set(out, p, (float) FLOAT.get(out, p) + dx);
				FLOAT.set(out, p + 4, (float) FLOAT.get(out, p + 4) + dy);
				FLOAT.set(out, p + 8, (float) FLOAT.get(out, p + 8) + dz);
			}
		}
		buffer.put(base, out, 0, bytes);
		return result;
	}

	private QuadBuckets reorder(byte[] in, byte[] out, int quads, int stride) {
		int quadBytes = stride * VERTICES_PER_QUAD;
		byte[] buckets = quadBucket.length >= quads ? quadBucket : (quadBucket = new byte[Math.max(quads, quadBucket.length + (quadBucket.length >> 1))]);
		Arrays.fill(counts, 0);
		for (int b = 0; b < DirectionalFaceMask.FACING_COUNT; b++) {
			planes[b] = DirectionalFaceMask.isPositive(b) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
		}
		float[] q = quad;
		for (int i = 0, p = 0; i < quads; i++, p += quadBytes) {
			for (int v = 0, o = p; v < VERTICES_PER_QUAD; v++, o += stride) {
				q[v * 3] = (float) FLOAT.get(in, o);
				q[v * 3 + 1] = (float) FLOAT.get(in, o + 4);
				q[v * 3 + 2] = (float) FLOAT.get(in, o + 8);
			}
			int b = classify(q);
			buckets[i] = (byte) b;
			counts[b]++;
			if (b < DirectionalFaceMask.FACING_COUNT) {
				recordPlane(b, q);
			}
		}
		int[] offsets = QuadBuckets.layoutOffsets(counts);
		for (int b = 0; b < DirectionalFaceMask.BUCKET_COUNT; b++) {
			cursor[b] = offsets[QuadBuckets.slotOf(b)];
		}
		for (int i = 0, p = 0; i < quads; i++, p += quadBytes) {
			int target = cursor[buckets[i]]++;
			System.arraycopy(in, p, out, target * quadBytes, quadBytes);
		}
		return new QuadBuckets(offsets, planes.clone());
	}

	private void recordPlane(int bucket, float[] q) {
		int axis = DirectionalFaceMask.axisOf(bucket);
		float lo = Math.min(Math.min(q[axis], q[3 + axis]), Math.min(q[6 + axis], q[9 + axis]));
		float hi = Math.max(Math.max(q[axis], q[3 + axis]), Math.max(q[6 + axis], q[9 + axis]));
		if (DirectionalFaceMask.isPositive(bucket)) {
			planes[bucket] = Math.min(planes[bucket], lo);
		} else {
			planes[bucket] = Math.max(planes[bucket], hi);
		}
	}

	/**
	 * Bucket of one quad given as {@code x0,y0,z0, ..., x3,y3,z3} in emission order. The quad is
	 * rasterized as triangles {@code (0,1,2)} and {@code (2,3,0)}; both winding normals must
	 * point the same way along the quad's plane axis.
	 */
	public static int classify(float[] q) {
		int axis;
		if (samePlane(q, 0)) {
			axis = 0;
		} else if (samePlane(q, 1)) {
			axis = 1;
		} else if (samePlane(q, 2)) {
			axis = 2;
		} else {
			return DirectionalFaceMask.UNASSIGNED;
		}
		int sign = combine(windingSign(q, 0, 1, 2, axis), windingSign(q, 2, 3, 0, axis));
		if (sign == 0) {
			return DirectionalFaceMask.UNASSIGNED;
		}
		return switch (axis) {
			case 0 -> sign > 0 ? DirectionalFaceMask.EAST : DirectionalFaceMask.WEST;
			case 1 -> sign > 0 ? DirectionalFaceMask.UP : DirectionalFaceMask.DOWN;
			default -> sign > 0 ? DirectionalFaceMask.SOUTH : DirectionalFaceMask.NORTH;
		};
	}

	private static boolean samePlane(float[] q, int axis) {
		float c = q[axis];
		return Math.abs(q[3 + axis] - c) <= PLANE_EPSILON
			&& Math.abs(q[6 + axis] - c) <= PLANE_EPSILON
			&& Math.abs(q[9 + axis] - c) <= PLANE_EPSILON;
	}

	/** Sign of the {@code axis} component of {@code (vb - va) x (vc - va)}. */
	private static int windingSign(float[] q, int a, int b, int c, int axis) {
		// The two in-plane axes, in cyclic order so the cross product component keeps its sign.
		int u = (axis + 1) % 3;
		int w = (axis + 2) % 3;
		double bu = (double) q[b * 3 + u] - q[a * 3 + u];
		double bw = (double) q[b * 3 + w] - q[a * 3 + w];
		double cu = (double) q[c * 3 + u] - q[a * 3 + u];
		double cw = (double) q[c * 3 + w] - q[a * 3 + w];
		double n = bu * cw - bw * cu;
		return n > 0 ? 1 : n < 0 ? -1 : 0;
	}

	/** Agreeing or one-degenerate signs give that sign; conflicting or fully degenerate give 0. */
	private static int combine(int s1, int s2) {
		if (s1 == 0) {
			return s2;
		}
		if (s2 == 0 || s2 == s1) {
			return s1;
		}
		return 0;
	}

	private static byte[] grow(byte[] array, int needed) {
		if (array.length >= needed) {
			return array;
		}
		return new byte[Math.max(needed, array.length + (array.length >> 1))];
	}
}
