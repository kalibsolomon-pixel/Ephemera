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

	private DiagnosticsHud() {}

	public static void render(GuiGraphicsExtractor g) {
		if (!VisualsRuntime.cfg().diagnosticsHudOverlay) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		FrameMetrics.Snapshot s = VisualsDiagnostics.snapshot();
		String version = EphemeraApi.get().map(EphemeraApi.Service::version).orElse("");
		String[] lines = new String[4];
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
			int color = i == 0 ? UiTheme.ACCENT : i == 1 ? lowColor(s.onePercentLowFps()) : UiTheme.TEXT;
			int textX = MARGIN + PAD;
			if (i == 0) {
				UiTheme.drawLogo(g, textX, y, logoSize);
				textX += logoSize + logoGap;
			}
			g.text(font, lines[i], textX, y, color, true);
			y += lineH;
		}
	}

	private static int lowColor(double onePercentLow) {
		if (onePercentLow <= 0.0) {
			return UiTheme.TEXT;
		}
		return onePercentLow >= 60.0 ? UiTheme.GOOD : onePercentLow >= 30.0 ? UiTheme.WARN : UiTheme.BAD;
	}
}
