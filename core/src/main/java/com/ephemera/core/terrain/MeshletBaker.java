package com.ephemera.core.terrain;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java off-thread baker that converts Minecraft's 28-byte {@code DefaultVertexFormat.BLOCK}
 * quads into compact 16-byte {@code uvec4} vertices (64 bytes/quad, zero index buffer) grouped
 * into 32-quad hardware meshlets (128 vertices, 64 triangles — one AMD Wave32 / NVIDIA Warp32)
 * with conservative 8-bit local AABBs for GPU compute frustum culling.
 *
 * <h3>Packed 16-byte Vertex ({@code uvec4})</h3>
 * <ul>
 *   <li>{@code w0}: {@code int16(round(x * 1024)) | (int16(round(y * 1024)) << 16)}</li>
 *   <li>{@code w1}: {@code int16(round(z * 1024)) | ((uv2_x & 0xFF) << 16) | ((uv2_y & 0xFF) << 24)}</li>
 *   <li>{@code w2}: raw RGBA8 UNORM color word</li>
 *   <li>{@code w3}: {@code uint16(round(u * 32768)) | (uint16(round(v * 32768)) << 16)}</li>
 * </ul>
 *
 * <h3>Packed 16-byte Meshlet Descriptor ({@code uvec4})</h3>
 * <ul>
 *   <li>{@code w0}: {@code firstQuad} (section-local here; relocated by GPU arena on upload)</li>
 *   <li>{@code w1}: {@code (sectionSlot << 10) | (bucket << 6) | quadCount} ({@code quadCount} in 1..32, {@code bucket} in 0..6)</li>
 *   <li>{@code w2}: {@code minX_u8 | (minY_u8 << 8) | (minZ_u8 << 16) | (maxX_u8 << 24)}</li>
 *   <li>{@code w3}: {@code maxY_u8 | (maxZ_u8 << 8) | (layerIndex << 16)}</li>
 * </ul>
 *
 * <p>Any layer containing out-of-range positions, non-representable UVs, or invalid light coords
 * is rejected cleanly so the caller seamlessly falls back to standard multi-draw indirect for
 * that layer with zero visual discrepancy.
 */
public final class MeshletBaker {
	public static final int BLOCK_STRIDE = 28;
	public static final int VERTICES_PER_QUAD = 4;
	public static final int QUADS_PER_MESHLET = 32;
	public static final int INTS_PER_VERTEX = 4;
	public static final int INTS_PER_QUAD = VERTICES_PER_QUAD * INTS_PER_VERTEX; // 16 ints = 64 bytes
	public static final int INTS_PER_MESHLET = 4; // 4 ints = 16 bytes
	public static final int BYTES_PER_QUAD = INTS_PER_QUAD * 4;
	public static final int BYTES_PER_MESHLET = INTS_PER_MESHLET * 4;

	public static final float POS_SCALE = 1024.0f;
	public static final float INV_POS_SCALE = 1.0f / POS_SCALE;
	public static final float UV_SCALE = 32768.0f;
	public static final float INV_UV_SCALE = 1.0f / UV_SCALE;

	/** Quantized meshlet AABB covers section-local coordinates in [-2.0, +18.0]. */
	public static final float AABB_MIN = -2.0f;
	public static final float AABB_MAX = 18.0f;
	public static final float AABB_RANGE = AABB_MAX - AABB_MIN; // 20.0f
	public static final float AABB_SCALE = 255.0f / AABB_RANGE;

	private static final float MAX_POS_ERROR = 1.0f / 1024.0f;
	private static final float MAX_UV_ERROR = 2.0e-5f;

	private static final VarHandle FLOAT = MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());
	private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

	private byte[] scratchBytes = new byte[0];
	private int[] scratchQuads = new int[0];
	private int[] scratchMeshlets = new int[0];

	/**
	 * Bakes up to two opaque layers (SOLID and/or CUTOUT) of one section into a single
	 * {@link BakedSectionMeshlets} payload.
	 *
	 * @param subtractDx X offset to subtract from vertex positions to recover section-local coords (0 if already local)
	 * @param subtractDy Y offset to subtract
	 * @param subtractDz Z offset to subtract
	 * @return baked meshlets if at least one layer packed cleanly, else {@code null}
	 */
	public BakedSectionMeshlets bake(int sectionX, int sectionY, int sectionZ,
									 float subtractDx, float subtractDy, float subtractDz,
									 ByteBuffer solidVerts, int solidVertCount, QuadBuckets solidBuckets,
									 ByteBuffer cutoutVerts, int cutoutVertCount, QuadBuckets cutoutBuckets) {
		int solidQuads = usableQuads(solidVerts, solidVertCount);
		int cutoutQuads = usableQuads(cutoutVerts, cutoutVertCount);
		if (solidQuads == 0 && cutoutQuads == 0) {
			return null;
		}
		int maxQuads = solidQuads + cutoutQuads;
		int maxMeshlets = estimateMaxMeshlets(solidQuads, solidBuckets) + estimateMaxMeshlets(cutoutQuads, cutoutBuckets);
		int[] qOut = ensureInts(scratchQuads, maxQuads * INTS_PER_QUAD);
		scratchQuads = qOut;
		int[] mOut = ensureInts(scratchMeshlets, maxMeshlets * INTS_PER_MESHLET);
		scratchMeshlets = mOut;

		float[] mergedPlanes = new float[DirectionalFaceMask.FACING_COUNT];
		for (int b = 0; b < DirectionalFaceMask.FACING_COUNT; b++) {
			mergedPlanes[b] = DirectionalFaceMask.isPositive(b) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
		}

		int layerMask = 0;
		int totalQuads = 0;
		int solidMeshlets = 0;
		int cutoutMeshlets = 0;

		if (solidQuads > 0) {
			int m = packLayer(solidVerts, solidQuads, solidBuckets, 0, subtractDx, subtractDy, subtractDz,
				qOut, totalQuads, mOut, 0, mergedPlanes);
			if (m > 0) {
				layerMask |= BakedSectionMeshlets.LAYER_SOLID_BIT;
				totalQuads += solidQuads;
				solidMeshlets = m;
			}
		}

		if (cutoutQuads > 0) {
			int m = packLayer(cutoutVerts, cutoutQuads, cutoutBuckets, 1, subtractDx, subtractDy, subtractDz,
				qOut, totalQuads, mOut, solidMeshlets, mergedPlanes);
			if (m > 0) {
				layerMask |= BakedSectionMeshlets.LAYER_CUTOUT_BIT;
				totalQuads += cutoutQuads;
				cutoutMeshlets = m;
			}
		}

		if (layerMask == 0 || totalQuads == 0) {
			return null;
		}

		int[] finalQuads = new int[totalQuads * INTS_PER_QUAD];
		System.arraycopy(qOut, 0, finalQuads, 0, finalQuads.length);
		int totalMeshlets = solidMeshlets + cutoutMeshlets;
		int[] finalMeshlets = new int[totalMeshlets * INTS_PER_MESHLET];
		System.arraycopy(mOut, 0, finalMeshlets, 0, finalMeshlets.length);

		return new BakedSectionMeshlets(sectionX, sectionY, sectionZ, layerMask,
			totalQuads, finalQuads, solidMeshlets, cutoutMeshlets, finalMeshlets, mergedPlanes);
	}

	private int packLayer(ByteBuffer buffer, int quadCount, QuadBuckets buckets, int layerIndex,
						  float subDx, float subDy, float subDz,
						  int[] qOut, int quadBase, int[] mOut, int meshletBase,
						  float[] mergedPlanes) {
		int bytes = quadCount * VERTICES_PER_QUAD * BLOCK_STRIDE;
		int pos = buffer.position();
		if (buffer.limit() - pos < bytes) {
			return 0;
		}
		byte[] raw = scratchBytes.length >= bytes ? scratchBytes
			: (scratchBytes = new byte[Math.max(bytes, scratchBytes.length + (scratchBytes.length >> 1))]);
		buffer.get(pos, raw, 0, bytes);

		// Pack all vertices first; abort cleanly if any vertex falls outside exact packed bounds.
		int qWrite = quadBase * INTS_PER_QUAD;
		for (int v = 0, off = 0, nVerts = quadCount * VERTICES_PER_QUAD; v < nVerts; v++, off += BLOCK_STRIDE) {
			float x = (float) FLOAT.get(raw, off) - subDx;
			float y = (float) FLOAT.get(raw, off + 4) - subDy;
			float z = (float) FLOAT.get(raw, off + 8) - subDz;
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
				|| x < AABB_MIN || x > AABB_MAX || y < AABB_MIN || y > AABB_MAX || z < AABB_MIN || z > AABB_MAX) {
				return 0;
			}
			int qx = Math.round(x * POS_SCALE);
			int qy = Math.round(y * POS_SCALE);
			int qz = Math.round(z * POS_SCALE);
			if (qx < -32768 || qx > 32767 || qy < -32768 || qy > 32767 || qz < -32768 || qz > 32767
				|| Math.abs(qx * INV_POS_SCALE - x) > MAX_POS_ERROR
				|| Math.abs(qy * INV_POS_SCALE - y) > MAX_POS_ERROR
				|| Math.abs(qz * INV_POS_SCALE - z) > MAX_POS_ERROR) {
				return 0;
			}

			int color = (int) INT.get(raw, off + 12);

			float u = (float) FLOAT.get(raw, off + 16);
			float tv = (float) FLOAT.get(raw, off + 20);
			if (!Float.isFinite(u) || !Float.isFinite(tv) || u < 0f || u > 1.9999f || tv < 0f || tv > 1.9999f) {
				return 0;
			}
			int qu = Math.round(u * UV_SCALE);
			int qv = Math.round(tv * UV_SCALE);
			if ((qu & ~0xFFFF) != 0 || (qv & ~0xFFFF) != 0
				|| Math.abs(qu * INV_UV_SCALE - u) > MAX_UV_ERROR
				|| Math.abs(qv * INV_UV_SCALE - tv) > MAX_UV_ERROR) {
				return 0;
			}

			int light = (int) INT.get(raw, off + 24);
			int lu = light & 0xFFFF;
			int lv = (light >>> 16) & 0xFFFF;
			if (lu > 255 || lv > 255) {
				return 0;
			}

			qOut[qWrite] = (qx & 0xFFFF) | ((qy & 0xFFFF) << 16);
			qOut[qWrite + 1] = (qz & 0xFFFF) | (lu << 16) | (lv << 24);
			qOut[qWrite + 2] = color;
			qOut[qWrite + 3] = qu | (qv << 16);
			qWrite += INTS_PER_VERTEX;
		}

		// Merge conservative directional planes if bucketed; otherwise treat all quads as UNASSIGNED.
		boolean hasBuckets = buckets != null && buckets.quadCount() == quadCount;
		int meshletCount = 0;
		if (hasBuckets) {
			float[] planes = buckets.planes();
			for (int b = 0; b < DirectionalFaceMask.FACING_COUNT; b++) {
				if (DirectionalFaceMask.isPositive(b)) {
					mergedPlanes[b] = Math.min(mergedPlanes[b], planes[b]);
				} else {
					mergedPlanes[b] = Math.max(mergedPlanes[b], planes[b]);
				}
			}
			int[] offsets = buckets.offsets();
			for (int s = 0; s < DirectionalFaceMask.BUCKET_COUNT; s++) {
				int start = offsets[s];
				int end = offsets[s + 1];
				if (end <= start) {
					continue;
				}
				int bucket = QuadBuckets.bucketAt(s);
				meshletCount += emitBucketMeshlets(raw, subDx, subDy, subDz, start, end,
					bucket, layerIndex, quadBase, mOut, meshletBase + meshletCount);
			}
		} else {
			meshletCount = emitBucketMeshlets(raw, subDx, subDy, subDz, 0, quadCount,
				DirectionalFaceMask.UNASSIGNED, layerIndex, quadBase, mOut, meshletBase);
		}
		return meshletCount;
	}

	private static int emitBucketMeshlets(byte[] raw, float subDx, float subDy, float subDz,
										  int startQuad, int endQuad, int bucket, int layerIndex,
										  int quadBase, int[] mOut, int meshletWriteStart) {
		int emitted = 0;
		int mIdx = meshletWriteStart * INTS_PER_MESHLET;
		for (int q = startQuad; q < endQuad; q += QUADS_PER_MESHLET) {
			int count = Math.min(QUADS_PER_MESHLET, endQuad - q);
			float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
			float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
			int off = q * VERTICES_PER_QUAD * BLOCK_STRIDE;
			for (int v = 0, nv = count * VERTICES_PER_QUAD; v < nv; v++, off += BLOCK_STRIDE) {
				float x = (float) FLOAT.get(raw, off) - subDx;
				float y = (float) FLOAT.get(raw, off + 4) - subDy;
				float z = (float) FLOAT.get(raw, off + 8) - subDz;
				if (x < minX) minX = x;
				if (y < minY) minY = y;
				if (z < minZ) minZ = z;
				if (x > maxX) maxX = x;
				if (y > maxY) maxY = y;
				if (z > maxZ) maxZ = z;
			}
			int qMinX = clampByte((int) Math.floor((minX - AABB_MIN) * AABB_SCALE));
			int qMinY = clampByte((int) Math.floor((minY - AABB_MIN) * AABB_SCALE));
			int qMinZ = clampByte((int) Math.floor((minZ - AABB_MIN) * AABB_SCALE));
			int qMaxX = clampByte((int) Math.ceil((maxX - AABB_MIN) * AABB_SCALE));
			int qMaxY = clampByte((int) Math.ceil((maxY - AABB_MIN) * AABB_SCALE));
			int qMaxZ = clampByte((int) Math.ceil((maxZ - AABB_MIN) * AABB_SCALE));

			mOut[mIdx] = quadBase + q;
			mOut[mIdx + 1] = (bucket << 6) | count;
			mOut[mIdx + 2] = qMinX | (qMinY << 8) | (qMinZ << 16) | (qMaxX << 24);
			mOut[mIdx + 3] = qMaxY | (qMaxZ << 8) | (layerIndex << 16);
			mIdx += INTS_PER_MESHLET;
			emitted++;
		}
		return emitted;
	}

	private static int usableQuads(ByteBuffer verts, int vertexCount) {
		if (verts == null || vertexCount <= 0 || vertexCount % VERTICES_PER_QUAD != 0) {
			return 0;
		}
		return vertexCount / VERTICES_PER_QUAD;
	}

	private static int estimateMaxMeshlets(int quads, QuadBuckets buckets) {
		if (quads <= 0) {
			return 0;
		}
		// At most 7 partial buckets + ceil(quads / 32)
		return (quads + QUADS_PER_MESHLET - 1) / QUADS_PER_MESHLET + (buckets != null ? DirectionalFaceMask.BUCKET_COUNT : 1);
	}

	private static int clampByte(int v) {
		return v < 0 ? 0 : Math.min(255, v);
	}

	private static int[] ensureInts(int[] arr, int needed) {
		if (arr.length >= needed) {
			return arr;
		}
		return new int[Math.max(needed, arr.length + (arr.length >> 1))];
	}
}
