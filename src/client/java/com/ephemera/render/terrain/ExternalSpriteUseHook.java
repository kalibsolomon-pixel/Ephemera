package com.ephemera.render.terrain;

/**
 * Marker mixed into every vanilla renderer that emits baked quads outside terrain
 * compilation. Visible-only animation stays off unless all of them carry it, because an
 * unmarked path could leave a held, framed or moving block's animated texture frozen.
 */
public interface ExternalSpriteUseHook {
}
