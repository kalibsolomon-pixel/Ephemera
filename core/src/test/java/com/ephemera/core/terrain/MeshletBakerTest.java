package com.ephemera.core.terrain;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

final class MeshletBakerTest {
	private static ByteBuffer blockQuads(int count, float baseX, float baseY, float baseZ) {
		ByteBuffer buf = ByteBuffer.allocate(count * 4 * MeshletBaker.BLOCK_STRIDE).order(ByteOrder.nativeOrder());
		for (int q = 0; q < count; q++) {
			float x = baseX + (q % 16);
			float y = baseY + ((q / 16) % 16);
			float z = baseZ + ((q / 256) % 16);
			// +Y facing quad: (0,0), (0,1), (1,1), (1,0)
			putVert(buf, x, y + 1f, z, 0xFF80C040, 0.125f, 0.25f, (240 << 16) | 160);
			putVert(buf, x, y + 1f, z + 1f, 0xFF80C040, 0.125f, 0.3125f, (240 << 16) | 160);
			putVert(buf, x + 1f, y + 1f, z + 1f, 0xFF80C040, 0.1875f, 0.3125f, (240 << 16) | 160);
			putVert(buf, x + 1f, y + 1f, z, 0xFF80C040, 0.1875f, 0.25f, (240 << 16) | 160);
		}
		buf.flip();
		return buf;
	}

	private static void putVert(ByteBuffer buf, float x, float y, float z, int color, float u, float v, int light) {
		buf.putFloat(x).putFloat(y).putFloat(z).putInt(color).putFloat(u).putFloat(v).putInt(light);
	}

	@Test
	void packsRoundTripVerticesAndSplitsInto32QuadMeshlets() {
		SectionVertexBaker vb = new SectionVertexBaker();
		ByteBuffer solid = blockQuads(70, 0f, 0f, 0f);
		// Reorder + rebase by region offset (160, 32, 240) just like RegionVertexBaking does
		QuadBuckets buckets = vb.bake(solid, 70 * 4, MeshletBaker.BLOCK_STRIDE, 160f, 32f, 240f, true);
		assertNotNull(buckets);

		MeshletBaker mb = new MeshletBaker();
		BakedSectionMeshlets baked = mb.bake(160, 32, 240, 160f, 32f, 240f,
			solid, 70 * 4, buckets, null, 0, null);
		assertNotNull(baked);
		assertEquals(BakedSectionMeshlets.LAYER_SOLID_BIT, baked.layerMask);
		assertEquals(70, baked.quadCount);
		// 70 quads all in UP bucket -> ceil(70/32) = 3 meshlets (32, 32, 6)
		assertEquals(3, baked.solidMeshletCount);
		assertEquals(0, baked.cutoutMeshletCount);

		// Verify meshlet descriptor counts & bucket id
		assertEquals(0, baked.meshletWords[0]);
		assertEquals((DirectionalFaceMask.UP << 6) | 32, baked.meshletWords[1]);
		assertEquals(32, baked.meshletWords[4]);
		assertEquals((DirectionalFaceMask.UP << 6) | 32, baked.meshletWords[5]);
		assertEquals(64, baked.meshletWords[8]);
		assertEquals((DirectionalFaceMask.UP << 6) | 6, baked.meshletWords[9]);

		// Verify first vertex round-trip decoding (matches terrain.mesh.glsl!)
		int w0 = baked.quadWords[0];
		int w1 = baked.quadWords[1];
		int w2 = baked.quadWords[2];
		int w3 = baked.quadWords[3];
		float dx = (short) (w0 & 0xFFFF) * MeshletBaker.INV_POS_SCALE;
		float dy = (short) (w0 >>> 16) * MeshletBaker.INV_POS_SCALE;
		float dz = (short) (w1 & 0xFFFF) * MeshletBaker.INV_POS_SCALE;
		int lu = (w1 >>> 16) & 0xFF;
		int lv = (w1 >>> 24) & 0xFF;
		float du = (w3 & 0xFFFF) * MeshletBaker.INV_UV_SCALE;
		float dv = (w3 >>> 16) * MeshletBaker.INV_UV_SCALE;
		assertEquals(0f, dx, 1e-4f);
		assertEquals(1f, dy, 1e-4f);
		assertEquals(0f, dz, 1e-4f);
		assertEquals(160, lu);
		assertEquals(240, lv);
		assertEquals(0xFF80C040, w2);
		assertEquals(0.125f, du, 1e-6f);
		assertEquals(0.25f, dv, 1e-6f);
	}

	@Test
	void packsBothSolidAndCutoutAndRejectsOutOfRangeGracefully() {
		MeshletBaker mb = new MeshletBaker();
		ByteBuffer solid = blockQuads(4, 0f, 0f, 0f);
		ByteBuffer badCutout = blockQuads(1, 50f, 0f, 0f); // outside [-2, +18]
		BakedSectionMeshlets baked = mb.bake(0, 0, 0, 0f, 0f, 0f,
			solid, 16, null, badCutout, 4, null);
		assertNotNull(baked);
		// Solid packed cleanly, out-of-range cutout gracefully excluded so fallback draws cutout
		assertEquals(BakedSectionMeshlets.LAYER_SOLID_BIT, baked.layerMask);
		assertEquals(1, baked.solidMeshletCount);
		assertEquals(0, baked.cutoutMeshletCount);
	}

	@Test
	void slabAllocatorAllocatesCoalescesAndGrows() {
		MeshletSlabAllocator alloc = new MeshletSlabAllocator(100);
		long a = alloc.alloc(30);
		long b = alloc.alloc(40);
		long c = alloc.alloc(30);
		assertEquals(MeshletSlabAllocator.INVALID, alloc.alloc(1));
		assertEquals(100, alloc.used());
		alloc.free(b);
		alloc.free(a);
		assertEquals(1, alloc.freeBlockCount()); // coalesced [0..70)
		alloc.free(c);
		assertEquals(0, alloc.used());
		assertEquals(1, alloc.freeBlockCount()); // coalesced [0..100)
		long all = alloc.alloc(100);
		assertEquals(0, MeshletSlabAllocator.offsetOf(all));
		alloc.grow(200);
		long extra = alloc.alloc(100);
		assertEquals(100, MeshletSlabAllocator.offsetOf(extra));
		assertEquals(200, alloc.used());
	}

	@Test
	void vulkanFrustumMathMatchesPerspectiveClipVolume() {
		// Standard perspective looking down -Z (right-handed, Vulkan Z in [0, 1], Y flipped like Vulkan)
		float fov = (float) Math.toRadians(70.0);
		float aspect = 16f / 9f;
		float near = 0.05f;
		float far = 256.0f;
		float f = 1.0f / (float) Math.tan(fov * 0.5);
		float[] m = new float[16];
		m[0] = f / aspect;
		m[5] = -f; // Vulkan Y flip
		m[10] = far / (near - far);
		m[11] = -1.0f;
		m[14] = (far * near) / (near - far);

		float[] planes = new float[VulkanFrustumMath.PLANE_FLOATS];
		VulkanFrustumMath.extractPlanes(m, true, planes);

		// Box directly ahead at z = -10 is visible
		assertTrue(VulkanFrustumMath.boxIntersects(planes, -1f, -1f, -11f, 1f, 1f, -9f));
		// Box behind camera at z = +10 is culled
		assertFalse(VulkanFrustumMath.boxIntersects(planes, -1f, -1f, 9f, 1f, 1f, 11f));
		// Box far to the right at x = +50, z = -10 is culled
		assertFalse(VulkanFrustumMath.boxIntersects(planes, 49f, -1f, -11f, 51f, 1f, -9f));
		// Box beyond far plane at z = -300 is culled
		assertFalse(VulkanFrustumMath.boxIntersects(planes, -1f, -1f, -310f, 1f, 1f, -290f));
	}
}
