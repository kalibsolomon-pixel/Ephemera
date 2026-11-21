package com.ephemera.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Dark-glass palette shared by the settings screen and the diagnostics HUD (ARGB). */
final class UiTheme {
	static final int DIM = 0xA0070910;
	static final int PANEL = 0xE60E1118;
	static final int PANEL_EDGE = 0x40FFFFFF;
	static final int HEADER = 0xF0141822;
	static final int ROW = 0x12FFFFFF;
	static final int ROW_HOVER = 0x26FFFFFF;
	static final int TRACK = 0x40FFFFFF;
	static final int ACCENT = 0xFF6FB7FF;
	static final int ACCENT_SOFT = 0x556FB7FF;
	static final int TEXT = 0xFFE8ECF4;
	static final int TEXT_DIM = 0xFF8C95A8;
	static final int GOOD = 0xFF6EE7A0;
	static final int WARN = 0xFFFFC857;
	static final int BAD = 0xFFFF6B6B;
	static final int HUD_BACK = 0x90080A10;

	private UiTheme() {}

	/** One-pixel rectangle outline. */
	static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}
}
