package com.ephemera.render.gui;

/**
 * Implemented on {@code Font$PreparedTextBuilder} so {@link WorldPreparedTextCache} can skip
 * caching lines containing obfuscated ({@code §k}) glyphs whose characters change every frame.
 */
public interface PreparedTextCacheable {
	boolean ephemera$isCacheable();
}
