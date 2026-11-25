package com.ephemera.engine;

/**
 * Duck-typed cached interning hash for immutable {@code VoxelShape}s, implemented by
 * {@code VoxelShapeHashMixin}. A stored hash is consistent with {@code StateInterning}'s shape
 * equality, so equal shapes always produce equal cached hashes.
 */
public interface ShapeHashCache {
	/** 0 until the hash has been computed once for this shape. */
	int ephemera$cachedShapeHash();

	void ephemera$setCachedShapeHash(int hash);
}
