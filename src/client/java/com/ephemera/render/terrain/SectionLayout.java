package com.ephemera.render.terrain;

import com.ephemera.core.terrain.BakedSectionMeshlets;
import com.ephemera.core.terrain.QuadBuckets;
import com.ephemera.core.terrain.RegionKey;
import org.jspecify.annotations.Nullable;

/**
 * How a compiled section's vertices were laid out by {@link RegionVertexBaking}. Every mesh
 * remembers the exact origin its positions are relative to, so the renderer always uploads
 * the matching {@code ChunkSection} position no matter how the config changed since the
 * mesh was compiled.
 */
public final class SectionLayout {
	/** Block origin of the section the mesh was compiled for. */
	public final int sectionX;
	public final int sectionY;
	public final int sectionZ;
	/** Origin vertex positions are relative to: the region origin when region-relative, else the section origin. */
	public final int originX;
	public final int originY;
	public final int originZ;
	public final boolean regionRelative;
	/** {@link RegionKey} of the origin, fixed at compile time. */
	public final long regionKey;
	private final QuadBuckets @Nullable [] buckets;
	/** Optional compact meshlet payload baked for Vulkan {@code VK_EXT_mesh_shader} terrain rendering. */
	public volatile @Nullable BakedSectionMeshlets meshlets;

	SectionLayout(int sectionX, int sectionY, int sectionZ, boolean regionRelative,
				  QuadBuckets @Nullable [] buckets, @Nullable BakedSectionMeshlets meshlets) {
		this.sectionX = sectionX;
		this.sectionY = sectionY;
		this.sectionZ = sectionZ;
		this.regionRelative = regionRelative;
		this.originX = regionRelative ? RegionKey.originBlockX(sectionX) : sectionX;
		this.originY = regionRelative ? RegionKey.originBlockY(sectionY) : sectionY;
		this.originZ = regionRelative ? RegionKey.originBlockZ(sectionZ) : sectionZ;
		this.regionKey = RegionKey.fromBlock(originX, originY, originZ);
		this.buckets = buckets;
		this.meshlets = meshlets;
	}

	/**
	 * Releases the CPU-side baked meshlet arrays once uploaded to Vulkan VRAM, returning the
	 * approximate heap bytes reclaimed so duplicate geometry never sits in both RAM and VRAM.
	 */
	public long releaseCpuMeshlets() {
		BakedSectionMeshlets m = this.meshlets;
		if (m == null) {
			return 0L;
		}
		this.meshlets = null;
		return (long) m.quadWords.length * 4L + (long) m.meshletWords.length * 4L + 96L;
	}

	/** Directional bucket layout of a sequentially indexed layer, or null when it was not reordered. */
	public @Nullable QuadBuckets buckets(int layerOrdinal) {
		QuadBuckets[] b = buckets;
		return b != null ? b[layerOrdinal] : null;
	}

	public long regionKey() {
		return regionKey;
	}
}
