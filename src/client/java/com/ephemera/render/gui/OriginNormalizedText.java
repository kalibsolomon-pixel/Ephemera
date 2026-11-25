package com.ephemera.render.gui;

/**
 * Implemented by {@code GuiTextRenderStateCacheMixin} on {@code GuiTextRenderState} when its
 * {@code Font.PreparedText} was prepared at origin {@code (0, 0)} so scrolling text (such as chat
 * lines shifting up every new message) hits {@link WorldPreparedTextCache} across Y offsets while
 * {@code GuiTextBatcher} applies the {@code (x, y)} translation in the draw matrix.
 */
public interface OriginNormalizedText {
	boolean ephemera$isOriginNormalized();
	int ephemera$offsetX();
	int ephemera$offsetY();
}
