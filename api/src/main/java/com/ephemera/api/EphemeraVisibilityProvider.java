package com.ephemera.api;

/**
 * Zero-allocation visibility and occlusion queries shared with Aurora Client
 * (e.g., hitboxes, nametags, minimap, HUD overlays) so no downstream mod ever
 * duplicates frustum or opaque-sweep occlusion work.
 */
public interface EphemeraVisibilityProvider {
	boolean isSectionVisible(int sectionX, int sectionY, int sectionZ);

	boolean isBoxOccluded(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

	long terrainRevisionStamp();

	int visibleSectionCount();

	int occludedEntityCount();

	int occludedBlockEntityCount();
}
