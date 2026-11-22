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
	static final int LOGO_BG = 0xFF000000;
	static final int LOGO_FG = 0xFFFFFFFF;

	private UiTheme() {}

	/** One-pixel rectangle outline. */
	static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/**
	 * Draws the minimalist Ephemera stylized "E" badge matching {@code assets/ephemera/icon.png}:
	 * plain black square with crisp white spine, top/bottom bars, and detached middle bar.
	 */
	static void drawLogo(GuiGraphicsExtractor g, int x, int y, int size) {
		if (size < 7) {
			return;
		}
		g.fill(x, y, x + size, y + size, LOGO_BG);

		int pad = Math.max(2, size / 5);
		int inner = size - pad * 2;
		int barH = Math.max(1, inner / 5);
		int spineW = Math.max(1, inner / 4);
		int ix = x + pad;
		int iy = y + pad;
		int bottomY = y + size - pad - barH;
		int midY = y + (size - barH) / 2;

		// Left vertical spine
		g.fill(ix, iy, ix + spineW, y + size - pad, LOGO_FG);
		// Top bar with subtle angled tip
		g.fill(ix, iy, ix + inner, iy + barH, LOGO_FG);
		if (barH >= 2) {
			g.fill(ix + inner - 1, iy + barH - 1, ix + inner, iy + barH, LOGO_BG);
		}
		// Detached middle bar
		int midGap = size >= 14 ? 2 : 1;
		int midRight = ix + inner - Math.max(1, inner / 8);
		g.fill(ix + spineW + midGap, midY, Math.max(ix + spineW + midGap + 1, midRight), midY + barH, LOGO_FG);
		// Bottom bar with subtle angled tip
		g.fill(ix, bottomY, ix + inner, bottomY + barH, LOGO_FG);
		if (barH >= 2) {
			g.fill(ix + inner - 1, bottomY + barH - 1, ix + inner, bottomY + barH, LOGO_BG);
		}
	}
}
