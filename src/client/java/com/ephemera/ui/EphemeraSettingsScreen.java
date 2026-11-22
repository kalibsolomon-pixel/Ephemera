package com.ephemera.ui;

import com.ephemera.EphemeraClient;
import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.ConfigStore;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.platform.RenderPlatform;
import com.ephemera.visuals.VisualsDiagnostics;
import com.ephemera.visuals.VisualsRuntime;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

/**
 * Tabbed dark-glass settings screen for every Ephemera option. Changes apply to the live config
 * immediately; "Done" (or Esc) persists them, "Revert" restores what the screen opened with.
 */
public final class EphemeraSettingsScreen extends Screen implements SettingRow.Host {
	private static final Set<String> SHADER_FIELDS = Set.of(
		"visualSmoothFog", "visualGradientDithering", "visualDitherStrength", "visualAtmosphericHorizon");
	private static final int HEADER_H = 34;
	private static final int TAB_H = 20;
	private static final int TIP_H = 24;
	private static final int FOOTER_H = 30;
	private static final int PAD = 8;

	private static SettingsTab lastTab = SettingsTab.PERFORMANCE;

	private final Screen parent;
	private final EphemeraConfig cfg = EphemeraCommon.config();
	private final EphemeraConfig snapshot = copyOf(cfg);

	private SettingsTab tab = lastTab;
	private List<SettingRow> rows = List.of();
	private int scroll;
	private boolean resetArmed;
	private boolean shadersDirty;
	private boolean watching;
	private SettingRow dragged;
	private Button resetButton;

	private int px;
	private int py;
	private int pw;
	private int ph;

	public EphemeraSettingsScreen(Screen parent) {
		super(Component.translatable("ephemera.settings.title"));
		this.parent = parent;
	}

	// --- layout ---

	private int contentTop() {
		return py + HEADER_H + TAB_H + 6;
	}

	private int contentBottom() {
		return py + ph - FOOTER_H - TIP_H - 4;
	}

	private int rowX() {
		return px + PAD;
	}

	private int rowW() {
		return pw - PAD * 2 - 8;
	}

	@Override
	protected void init() {
		pw = Math.max(340, Math.min(width - 24, 540));
		px = (width - pw) / 2;
		py = 10;
		ph = height - 20;

		if (!watching) {
			watching = true;
			VisualsDiagnostics.acquire();
		}
		if (rows.isEmpty()) {
			rows = tab.rows(cfg);
		}

		int gap = 6;
		int bw = (pw - PAD * 2 - gap * 2) / 3;
		int by = py + ph - FOOTER_H + 4;
		resetButton = addRenderableWidget(Button.builder(Component.translatable("ephemera.ui.reset"), b -> onReset(b))
			.bounds(px + PAD, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("ephemera.ui.revert"), b -> onRevert())
			.bounds(px + PAD + bw + gap, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("ephemera.ui.done"), b -> onClose())
			.bounds(px + PAD + (bw + gap) * 2, by, bw, 20).build());
		clampScroll();
	}

	@Override
	public void removed() {
		if (watching) {
			watching = false;
			VisualsDiagnostics.release();
		}
		super.removed();
	}

	@Override
	public void onClose() {
		ConfigStore.sanitize(cfg);
		EphemeraCommon.saveConfig();
		RenderPlatform.refreshBlockAtlasAnisotropy();
		minecraft.gui.setScreen(parent);
	}

	// --- actions ---

	private void onReset(Button button) {
		if (!resetArmed) {
			resetArmed = true;
			button.setMessage(Component.translatable("ephemera.ui.resetConfirm"));
			return;
		}
		resetArmed = false;
		button.setMessage(Component.translatable("ephemera.ui.reset"));
		copyInto(cfg, new EphemeraConfig());
		afterBulkChange();
	}

	private void onRevert() {
		copyInto(cfg, snapshot);
		afterBulkChange();
	}

	private void afterBulkChange() {
		ConfigStore.sanitize(cfg);
		VisualsRuntime.invalidate();
		shadersDirty = true;
		rows = tab.rows(cfg);
		RenderPlatform.refreshBlockAtlasAnisotropy();
		clampScroll();
	}

	private void selectTab(SettingsTab next) {
		if (next == tab) {
			return;
		}
		tab = next;
		lastTab = next;
		rows = next.rows(cfg);
		scroll = 0;
		dragged = null;
		click();
	}

	private void click() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	@Override
	public Font font() {
		return font;
	}

	@Override
	public void changed(String field) {
		VisualsRuntime.invalidate();
		if (SHADER_FIELDS.contains(field)) {
			shadersDirty = true;
		}
		if (field.equals("visualCrispMipmaps") || field.equals("visualAnisotropicLevel")) {
			try {
				RenderPlatform.refreshBlockAtlasAnisotropy();
			} catch (RuntimeException e) {
				EphemeraClient.LOGGER.warn("Could not refresh anisotropic filtering: {}", e.toString());
			}
		}
	}

	// --- rendering ---

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, UiTheme.DIM);
		g.fill(px, py, px + pw, py + ph, UiTheme.PANEL);
		UiTheme.frame(g, px, py, pw, ph, UiTheme.PANEL_EDGE);
		g.fill(px + 1, py + 1, px + pw - 1, py + HEADER_H, UiTheme.HEADER);

		UiTheme.drawLogo(g, px + PAD + 2, py + 7, 20);
		Component title = Component.translatable("ephemera.settings.title");
		g.text(font, title, px + PAD + 28, py + 8, UiTheme.TEXT, true);
		Component subtitle = Component.translatable(tab.titleKey());
		g.text(font, subtitle, px + PAD + 28, py + 20, UiTheme.TEXT_DIM, true);
		String backend = VisualsDiagnostics.backendName();
		int chipW = font.width(backend) + 14;
		int chipX = px + pw - chipW - PAD;
		g.fill(chipX, py + 9, chipX + chipW, py + 23, UiTheme.ACCENT_SOFT);
		UiTheme.frame(g, chipX, py + 9, chipW, 14, UiTheme.ACCENT);
		g.text(font, backend, chipX + 7, py + 12, UiTheme.TEXT, true);

		renderTabs(g, mouseX, mouseY);
		renderRows(g, mouseX, mouseY);
		renderTooltip(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private void renderTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		SettingsTab[] tabs = SettingsTab.values();
		int barX = px + PAD;
		int barW = pw - PAD * 2;
		int tabW = barW / tabs.length;
		int y = py + HEADER_H + 2;
		g.fill(barX, y + TAB_H - 1, barX + barW, y + TAB_H, UiTheme.PANEL_EDGE);
		for (int i = 0; i < tabs.length; i++) {
			SettingsTab t = tabs[i];
			int x = barX + i * tabW;
			boolean selected = t == tab;
			boolean hover = mouseX >= x && mouseX < x + tabW && mouseY >= y && mouseY < y + TAB_H;
			if (selected) {
				g.fill(x, y, x + tabW, y + TAB_H - 1, UiTheme.ACCENT_SOFT);
			} else if (hover) {
				g.fill(x, y, x + tabW, y + TAB_H - 1, UiTheme.ROW);
			}
			Component full = Component.translatable(t.titleKey());
			Component label = font.width(full) + 8 <= tabW ? full : Component.translatable(t.shortKey());
			g.text(font, label, x + (tabW - font.width(label)) / 2, y + (TAB_H - font.lineHeight) / 2 - 1,
				selected ? UiTheme.TEXT : UiTheme.TEXT_DIM, true);
			if (selected) {
				g.fill(x, y + TAB_H - 2, x + tabW, y + TAB_H, UiTheme.ACCENT);
			}
		}
	}

	private void renderRows(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int top = contentTop();
		int bottom = contentBottom();
		g.enableScissor(px + 1, top, px + pw - 1, bottom);
		int y = top - scroll;
		int x = rowX();
		int w = rowW();
		for (SettingRow row : rows) {
			int h = row.height();
			if (y + h > top && y < bottom) {
				boolean hovered = isHover(mouseX, mouseY, x, y, w, h, top, bottom);
				row.render(this, g, x, y, w, mouseX, mouseY, hovered);
			}
			y += h;
		}
		g.disableScissor();

		int total = totalHeight();
		int view = bottom - top;
		if (total > view) {
			int barX = px + pw - 7;
			g.fill(barX, top, barX + 3, bottom, UiTheme.ROW);
			int thumb = Math.max(16, view * view / total);
			int thumbY = top + (int) ((long) (view - thumb) * scroll / Math.max(1, total - view));
			g.fill(barX, thumbY, barX + 3, thumbY + thumb, UiTheme.ACCENT);
		}
	}

	private void renderTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y = py + ph - FOOTER_H - TIP_H;
		g.fill(px + PAD, y, px + pw - PAD, y + TIP_H - 2, UiTheme.ROW);
		Component text = null;
		int color = UiTheme.TEXT_DIM;
		SettingRow hovered = rowAt(mouseX, mouseY);
		if (hovered != null && hovered.tooltipKey() != null) {
			text = Component.translatable(hovered.tooltipKey());
		} else if (shadersDirty) {
			text = Component.translatable("ephemera.ui.shaderReload");
			color = UiTheme.WARN;
		} else {
			text = Component.translatable("ephemera.ui.hint");
		}
		g.textWithWordWrap(font, text, px + PAD + 4, y + 3, pw - PAD * 2 - 8, color);
	}

	// --- row geometry ---

	private int totalHeight() {
		int total = 0;
		for (SettingRow row : rows) {
			total += row.height();
		}
		return total;
	}

	private void clampScroll() {
		int max = Math.max(0, totalHeight() - (contentBottom() - contentTop()));
		scroll = Math.max(0, Math.min(max, scroll));
	}

	private boolean isHover(double mouseX, double mouseY, int x, int y, int w, int h, int top, int bottom) {
		return mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom);
	}

	private SettingRow rowAt(double mouseX, double mouseY) {
		int top = contentTop();
		int bottom = contentBottom();
		int y = top - scroll;
		for (SettingRow row : rows) {
			int h = row.height();
			if (isHover(mouseX, mouseY, rowX(), y, rowW(), h, top, bottom)) {
				return row;
			}
			y += h;
		}
		return null;
	}

	private int rowTop(SettingRow target) {
		int y = contentTop() - scroll;
		for (SettingRow row : rows) {
			if (row == target) {
				return y;
			}
			y += row.height();
		}
		return y;
	}

	// --- input ---

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		int tabY = py + HEADER_H + 2;
		if (my >= tabY && my < tabY + TAB_H && mx >= px + PAD && mx < px + pw - PAD) {
			SettingsTab[] tabs = SettingsTab.values();
			int tabW = (pw - PAD * 2) / tabs.length;
			int idx = (int) ((mx - (px + PAD)) / tabW);
			if (idx >= 0 && idx < tabs.length) {
				selectTab(tabs[idx]);
				return true;
			}
		}
		SettingRow row = rowAt(mx, my);
		if (row != null && row.mouseClicked(this, rowX(), rowTop(row), rowW(), mx, my, event.button())) {
			dragged = row;
			click();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragged != null && dragged.mouseDragged(this, rowX(), rowW(), event.x())) {
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		for (SettingRow row : rows) {
			row.mouseReleased();
		}
		dragged = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= px && mouseX < px + pw && mouseY >= contentTop() && mouseY < contentBottom()) {
			scroll -= (int) Math.round(scrollY * SettingRow.HEIGHT * 2);
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_TAB) {
			SettingsTab[] tabs = SettingsTab.values();
			int step = event.hasShiftDown() ? tabs.length - 1 : 1;
			selectTab(tabs[(tab.ordinal() + step) % tabs.length]);
			return true;
		}
		int page = contentBottom() - contentTop();
		switch (key) {
			case InputConstants.KEY_DOWN -> scroll += SettingRow.HEIGHT;
			case InputConstants.KEY_UP -> scroll -= SettingRow.HEIGHT;
			case InputConstants.KEY_PAGEDOWN -> scroll += page;
			case InputConstants.KEY_PAGEUP -> scroll -= page;
			default -> {
				return super.keyPressed(event);
			}
		}
		clampScroll();
		return true;
	}

	// --- config copying ---

	private static EphemeraConfig copyOf(EphemeraConfig source) {
		EphemeraConfig copy = new EphemeraConfig();
		copyInto(copy, source);
		return copy;
	}

	private static void copyInto(EphemeraConfig target, EphemeraConfig source) {
		for (Field f : EphemeraConfig.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
				continue;
			}
			try {
				f.set(target, f.get(source));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
