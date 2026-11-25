package com.ephemera.render.item;

import com.ephemera.render.fastwrite.CachedItemQuadList;

/** Duck interface on 26.2 {@code ItemStackRenderState.LayerRenderState} for zero-copy shared quads. */
public interface LayerSharedQuadsAccess {
	void ephemera$setSharedQuads(CachedItemQuadList quads);
}
