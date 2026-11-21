package com.ephemera.core.terrain;

/**
 * Compact, GPU-ready meshlet geometry baked off-thread for one 16x16x16 terrain section's
 * opaque layers (SOLID and CUTOUT) for cross-vendor {@code VK_EXT_mesh_shader} rendering
 * (AMD RDNA 2/3/4 and NVIDIA Turing/Ampere/Ada/Blackwell).
 *
 * <p>Vertices are packed into 16-byte {@code uvec4} records (64 bytes per quad, zero index
 * buffer) and grouped into 32-quad meshlets (128 vertices / 64 triangles = 1 AMD Wave32 /
 * 1 NVIDIA Warp32) with conservative 8-bit quantized local AABBs for sub-section GPU frustum
 * culling. SOLID meshlets are stored first ({@code [0, solidMeshletCount)}), followed by
 * CUTOUT meshlets ({@code [solidMeshletCount, totalMeshlets())}).
 *
 * <p>Immutable once created; safe to hand across chunk-builder and render threads.
 */
public final class BakedSectionMeshlets {
	/** Layer bit for SOLID in {@link #layerMask}. */
	public static final int LAYER_SOLID_BIT = 1 << 0;
	/** Layer bit for CUTOUT in {@link #layerMask}. */
	public static final int LAYER_CUTOUT_BIT = 1 << 1;

	public final int sectionX;
	public final int sectionY;
	public final int sectionZ;
	/** Bitmask of layers packed into this meshlet payload ({@link #LAYER_SOLID_BIT} / {@link #LAYER_CUTOUT_BIT}). */
	public final int layerMask;
	/** Total quads across all baked layers. */
	public final int quadCount;
	/** Packed quad vertex words: {@code quadCount * MeshletBaker.INTS_PER_QUAD} ints (64 bytes/quad). */
	public final int[] quadWords;
	public final int solidMeshletCount;
	public final int cutoutMeshletCount;
	/**
	 * Packed meshlet descriptors: {@code totalMeshlets() * MeshletBaker.INTS_PER_MESHLET} ints
	 * (16 bytes/meshlet). {@code w0} holds the section-local first quad index and {@code w1}
	 * holds {@code (bucket << 6) | quadCount}; the render-thread arena adds the global quad base
	 * and ORs {@code (sectionSlot << 10)} during staging upload.
	 */
	public final int[] meshletWords;
	/**
	 * Conservative directional planes (6 floats in section-local coordinates, indexed by
	 * {@link DirectionalFaceMask} 0..5) covering all baked layers of this section.
	 */
	public final float[] planes;

	BakedSectionMeshlets(int sectionX, int sectionY, int sectionZ, int layerMask,
						 int quadCount, int[] quadWords,
						 int solidMeshletCount, int cutoutMeshletCount, int[] meshletWords,
						 float[] planes) {
		this.sectionX = sectionX;
		this.sectionY = sectionY;
		this.sectionZ = sectionZ;
		this.layerMask = layerMask;
		this.quadCount = quadCount;
		this.quadWords = quadWords;
		this.solidMeshletCount = solidMeshletCount;
		this.cutoutMeshletCount = cutoutMeshletCount;
		this.meshletWords = meshletWords;
		this.planes = planes;
	}

	public int totalMeshlets() {
		return solidMeshletCount + cutoutMeshletCount;
	}

	public boolean hasLayer(int layerOrdinal) {
		return layerOrdinal >= 0 && layerOrdinal < 2 && (layerMask & (1 << layerOrdinal)) != 0;
	}
}
