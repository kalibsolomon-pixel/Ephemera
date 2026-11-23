package com.ephemera.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Flat gray-and-black palette shared by the settings screen and the diagnostics HUD (ARGB). */
final class UiTheme {
	/** Scrim over the world or panorama behind the full-page screen. */
	static final int BACKDROP = 0xD0080808;
	static final int SIDEBAR = 0xF40A0A0A;
	static final int SURFACE = 0xE8161616;
	static final int SURFACE_HOVER = 0xF0242424;
	static final int SURFACE_SUNKEN = 0xFF0C0C0C;
	static final int BORDER = 0xFF2E2E2E;
	static final int DIVIDER = 0x26FFFFFF;
	static final int TRACK = 0xFF303030;
	static final int TICK = 0xFF4C4C4C;
	/** Dark gray fill for active controls (switch on, selected segment, primary button). */
	static final int ACCENT = 0xFF4A4A4A;
	static final int ACCENT_HOVER = 0xFF5C5C5C;
	/** Text and knobs drawn on an {@link #ACCENT} fill. */
	static final int ON_ACCENT = 0xFFF2F2F2;
	static final int SLIDER_FILL = 0xFF808080;
	static final int CHIP = 0x30FFFFFF;
	static final int KNOB = 0xFFF2F2F2;
	static final int TEXT = 0xFFF2F2F2;
	static final int TEXT_DIM = 0xFF9C9C9C;
	static final int TEXT_FAINT = 0xFF5E5E5E;
	static final int GOOD = 0xFF34D399;
	static final int WARN = 0xFFFBBF24;
	static final int BAD = 0xFFF87171;
	static final int HUD_BACK = 0x90080A10;
	static final int LOGO_BG = 0xFF000000;
	static final int LOGO_FG = 0xFFFFFFFF;

	private static final String ELLIPSIS = "...";

	private UiTheme() {}

	/** Filled square-cornered rectangle. */
	static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, color);
	}

	/** One-pixel outline of a rectangle. */
	static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** {@code text} cut to {@code maxWidth} with a trailing ellipsis when it does not fit. */
	static String fit(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		int room = maxWidth - font.width(ELLIPSIS);
		if (room <= 0) {
			return "";
		}
		return font.plainSubstrByWidth(text, room).stripTrailing() + ELLIPSIS;
	}

	/** Vertical text offset that centers one line in a box of height {@code h}. */
	static int centerY(Font font, int y, int h) {
		return y + (h - font.lineHeight) / 2 + 1;
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
