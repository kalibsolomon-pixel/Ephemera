package com.ephemera.render.culling;

import com.ephemera.EphemeraClient;
import com.ephemera.EphemeraCommon;
import com.ephemera.api.EphemeraVisibilityProvider;

public final class EphemeraVisibilityService implements EphemeraVisibilityProvider {
    private static final EphemeraVisibilityService INSTANCE = new EphemeraVisibilityService();
    private EphemeraVisibilityService() { }
    public static void init() { EphemeraClient.setVisibilityProvider(INSTANCE); }
    @Override public boolean isSectionVisible(int x, int y, int z) {
        return !(EphemeraCommon.config().entityOcclusionCulling || EphemeraCommon.config().blockEntityOcclusionCulling)
                || EntityOcclusionManager.sectionVisible(x, y, z);
    }
    @Override public boolean isBoxOccluded(double x0, double y0, double z0, double x1, double y1, double z1) {
        return (EphemeraCommon.config().entityOcclusionCulling || EphemeraCommon.config().blockEntityOcclusionCulling)
                && EntityOcclusionManager.boxOccluded(x0, y0, z0, x1, y1, z1);
    }
    @Override public long terrainRevisionStamp() { return EntityOcclusionManager.terrainStamp(); }
    @Override public int visibleSectionCount() { return EntityOcclusionManager.visibleSections(); }
    @Override public int occludedEntityCount() { return EntityOcclusionManager.occludedEntities(); }
    @Override public int occludedBlockEntityCount() { return EntityOcclusionManager.occludedBlockEntities(); }
}
