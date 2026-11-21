package com.ephemera.render.culling;

/** Implemented by the direct fields added to EntityType and BlockEntityType. */
public interface RendererTypeCache {
    Object ephemera$renderer(Object dispatcher, long generation);
    void ephemera$renderer(Object dispatcher, long generation, Object renderer);
    void ephemera$clearRenderer();
}
