package com.ephemera.ui;

import com.ephemera.api.EphemeraApi;
import com.ephemera.api.EphemeraVisibilityProvider;
import com.ephemera.core.diagnostics.FrameMetrics;
import com.ephemera.visuals.VisualsDiagnostics;
import com.ephemera.visuals.VisualsRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** Compact top-left frame-time and culling readout, enabled by {@code diagnosticsHudOverlay}. */
public final class DiagnosticsHud {
	private static final int MARGIN = 4;
	private static final int PAD = 3;
	// Re-format the HUD text at 10 Hz instead of every frame: users enable this overlay to measure
	// lows, and per-frame String.format + Optional chains would inject the jitter they measure.
	private static final long TEXT_REFRESH_INTERVAL_NS = 100_000_000L;

	private static final String[] CACHED_LINES = new String[4];
	private static int cachedLowColor = UiTheme.TEXT;
	private static long cachedTextAtNs;

	private DiagnosticsHud() {}

	public static void render(GuiGraphicsExtractor g) {
		if (!VisualsRuntime.cfg().diagnosticsHudOverlay) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		long now = System.nanoTime();
		// The null guard covers nanoTime origins before the interval has elapsed at least once.
		if (CACHED_LINES[0] == null || now - cachedTextAtNs >= TEXT_REFRESH_INTERVAL_NS) {
			refreshLines(mc);
			// Stamp only after a successful refresh so a throwing refresh is retried next frame.
			cachedTextAtNs = now;
		}

		String[] lines = CACHED_LINES;
		Font font = mc.font;
		int logoSize = 9;
		int logoGap = 3;
		int width = 0;
		int count = 0;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (!line.isEmpty()) {
				int lineW = font.width(line) + (i == 0 ? logoSize + logoGap : 0);
				width = Math.max(width, lineW);
				count++;
			}
		}
		int lineH = font.lineHeight + 1;
		g.fill(MARGIN, MARGIN, MARGIN + width + PAD * 2, MARGIN + count * lineH + PAD * 2 - 1, UiTheme.HUD_BACK);
		int y = MARGIN + PAD;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].isEmpty()) {
				continue;
			}
			int color = i == 0 ? UiTheme.TEXT : i == 1 ? cachedLowColor : UiTheme.TEXT;
			int textX = MARGIN + PAD;
			if (i == 0) {
				UiTheme.drawLogo(g, textX, y, logoSize);
				textX += logoSize + logoGap;
			}
			g.text(font, lines[i], textX, y, color, true);
			y += lineH;
		}
	}

	private static void refreshLines(Minecraft mc) {
		String[] lines = CACHED_LINES;
		FrameMetrics.Snapshot s = VisualsDiagnostics.snapshot();
		String version = EphemeraApi.get().map(EphemeraApi.Service::version).orElse("");
		lines[0] = "Ephemera " + version + "  " + VisualsDiagnostics.backendName();
		lines[1] = s.samples() == 0
			? "FPS " + mc.getFps()
			: String.format(Locale.ROOT, "FPS %.0f  1%% low %.0f  0.1%% %.0f", s.avgFps(), s.onePercentLowFps(), s.pointOnePercentLowFps());
		lines[2] = s.samples() == 0
			? "..."
			: String.format(Locale.ROOT, "%.2f ms med  %.2f ms p99", s.medianMs(), s.p99Ms());
		EphemeraVisibilityProvider vis = VisualsDiagnostics.visibility();
		lines[3] = vis == null
			? ""
			: String.format(Locale.ROOT, "Sections %d  Occluded E %d  BE %d", vis.visibleSectionCount(), vis.occludedEntityCount(), vis.occludedBlockEntityCount());
		cachedLowColor = lowColor(s.onePercentLowFps());
	}

	private static int lowColor(double onePercentLow) {
		if (onePercentLow <= 0.0) {
			return UiTheme.TEXT;
		}
		return onePercentLow >= 60.0 ? UiTheme.GOOD : onePercentLow >= 30.0 ? UiTheme.WARN : UiTheme.BAD;
	}
}
