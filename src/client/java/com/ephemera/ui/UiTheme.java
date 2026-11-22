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
	static final int LOGO_BG = 0xFF0E1426;
	static final int LOGO_TOP = 0xFF93D8FF;
	static final int LOGO_MID = 0xFFE0F2FE;
	static final int LOGO_BOT = 0xFF818CF8;

	private UiTheme() {}

	/** One-pixel rectangle outline. */
	static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/**
	 * Draws the Ephemera stylized "E" badge matching {@code assets/ephemera/icon.png}:
	 * dark-glass backing, accent frame, vertical spine, top/bottom wings, and detached glowing middle bar.
	 */
	static void drawLogo(GuiGraphicsExtractor g, int x, int y, int size) {
		if (size < 7) {
			return;
		}
		g.fill(x, y, x + size, y + size, LOGO_BG);
		frame(g, x, y, size, size, ACCENT_SOFT);

		int pad = Math.max(2, size / 5);
		int inner = size - pad * 2;
		int barH = Math.max(1, inner / 5);
		int spineW = Math.max(1, inner / 4);
		int ix = x + pad;
		int iy = y + pad;
		int bottomY = y + size - pad - barH;
		int midY = y + (size - barH) / 2;

		// Left vertical spine
		g.fill(ix, iy, ix + spineW, y + size - pad, ACCENT);
		// Top prism wing (subtle stepped bevel at tip)
		g.fill(ix, iy, ix + inner, iy + barH, LOGO_TOP);
		if (barH >= 2) {
			g.fill(ix + inner - 1, iy + barH - 1, ix + inner, iy + barH, LOGO_BG);
		}
		// Detached glowing middle prism bar
		int midGap = size >= 14 ? 2 : 1;
		int midRight = ix + inner - Math.max(1, inner / 8);
		g.fill(ix + spineW + midGap, midY, Math.max(ix + spineW + midGap + 1, midRight), midY + barH, LOGO_MID);
		// Bottom prism wing
		g.fill(ix, bottomY, ix + inner, bottomY + barH, LOGO_BOT);
		if (barH >= 2) {
			g.fill(ix + inner - 1, bottomY + barH - 1, ix + inner, bottomY + barH, LOGO_BG);
		}
	}
}
