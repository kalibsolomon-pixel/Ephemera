package com.ephemera.render.culling;

/**
 * Duck interface on {@code EntityRenderDispatcher} scoping a single-entry identity cache to one
 * {@code LevelExtractor.extractVisibleEntities} pass.
 *
 * <p>Vanilla resolves each visible entity's renderer twice in immediate succession: once inside
 * {@code shouldRender} and again inside {@code extractEntity}. Reusing the resolved renderer by
 * exact entity identity within that single pass eliminates the second lookup for every entity
 * (including players/Avatars with model variants and modded entities with custom renderer dispatch)
 * while clearing the reference before the pass exits.
 */
public interface EntityExtractPassCache {
	void ephemera$beginExtractPass();

	void ephemera$endExtractPass();
}
