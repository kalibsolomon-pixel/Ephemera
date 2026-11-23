package com.ephemera.ui;

import com.ephemera.EphemeraClient;
import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.ConfigStore;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.platform.RenderPlatform;
import com.ephemera.visuals.VisualsDiagnostics;
import com.ephemera.visuals.VisualsRuntime;
import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.UnsupportedGraphicsWarningScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.GpuWarnlistManager;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Full-page settings screen that replaces vanilla Video Settings: a sidebar of Minecraft and
 * Ephemera pages, the option list, and a description panel for the hovered option. Changes apply
 * live; Done (or Esc) saves them, Undo restores what the screen opened with.
 */
public final class EphemeraSettingsScreen extends Screen implements SettingRow.Host {
	private static final Set<String> SHADER_FIELDS = Set.of(
		"visualSmoothFog", "visualGradientDithering", "visualDitherStrength", "visualAtmosphericHorizon");
	private static final String VERSION = FabricLoader.getInstance().getModContainer(EphemeraCommon.MOD_ID)
		.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
	private static final int BRAND_H = 36;
	private static final int NAV_ITEM_H = 18;
	private static final int FOOTER_H = 30;
	private static final int BUTTON_H = 18;
	private static final int PAD = 12;
	private static final int GAP = 12;
	private static final int MAX_ROWS_W = 500;

	private static SettingsTab lastTab = SettingsTab.DISPLAY;
	private static boolean openingVanilla;

	private final Screen parent;
	private final EphemeraConfig cfg = EphemeraCommon.config();
	private final EphemeraConfig snapshot = copyOf(cfg);
	private final VanillaVideoOptions vanilla;
	private final GpuWarnlistManager gpuWarnings;

	private SettingsTab tab = lastTab;
	private List<SettingRow> rows = List.of();
	private int scroll;
	private boolean resetArmed;
	private boolean shadersDirty;
	private boolean dirty;
	private boolean watching;
	private SettingRow dragged;
	private int lastMouseX;
	private int lastMouseY;

	// layout, recomputed in init()
	private int sidebarW;
	private int headerH;
	private int rowsX;
	private int rowsW;
	private int descX;
	private int descW;
	private int descH;
	private int contentTop;
	private int contentBottom;

	public EphemeraSettingsScreen(Screen parent) {
		super(Component.translatable("ephemera.settings.title"));
		this.parent = parent;
		this.vanilla = new VanillaVideoOptions(minecraft);
		this.gpuWarnings = minecraft.getGpuWarnlistManager();
		gpuWarnings.resetWarnings();
		if (vanilla.options.improvedTransparency().get()) {
			gpuWarnings.dismissWarning();
		}
	}

	/** True while the screen deliberately opens vanilla Video Settings, so it is not replaced. */
	public static boolean openingVanilla() {
		return openingVanilla;
	}

	// --- layout ---

	@Override
	protected void init() {
		sidebarW = Math.max(92, Math.min(132, width / 5));
		headerH = height >= 260 ? 38 : 26;
		contentTop = headerH + 4;
		int footerTop = height - FOOTER_H;
		int avail = width - sidebarW - PAD * 2;
		if (avail >= 300 + GAP + 150) {
			descW = Math.max(150, Math.min(230, avail * 3 / 10));
			rowsW = Math.min(MAX_ROWS_W, avail - descW - GAP);
			int left = sidebarW + PAD + (avail - rowsW - GAP - descW) / 2;
			rowsX = left;
			descX = left + rowsW + GAP;
			descH = 0;
			contentBottom = footerTop - 4;
		} else {
			descW = 0;
			rowsW = avail;
			rowsX = sidebarW + PAD;
			descX = rowsX;
			descH = height >= 250 ? 46 : 0;
			contentBottom = footerTop - 4 - (descH > 0 ? descH + 6 : 0);
		}

		if (!watching) {
			watching = true;
			VisualsDiagnostics.acquire();
		}
		if (rows.isEmpty()) {
			rows = tab.rows(cfg, vanilla);
		}
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
		vanilla.apply();
		minecraft.gui.setScreen(parent);
	}

	// --- actions ---

	private void onResetPage() {
		if (!resetArmed) {
			resetArmed = true;
			return;
		}
		resetArmed = false;
		for (SettingRow row : rows) {
			if (row instanceof SettingRow.OptionRow<?> option) {
				resetRow(option);
			}
		}
		afterBulkChange();
	}

	private <T> void resetRow(SettingRow.OptionRow<T> row) {
		row.set(this, row.binding.defaultValue());
	}

	private void onUndo() {
		copyInto(cfg, snapshot);
		vanilla.revert();
		dirty = false;
		afterBulkChange();
	}

	private void afterBulkChange() {
		ConfigStore.sanitize(cfg);
		VisualsRuntime.invalidate();
		shadersDirty = true;
		RenderPlatform.refreshBlockAtlasAnisotropy();
		clampScroll();
	}

	private void openVanilla() {
		openingVanilla = true;
		try {
			minecraft.gui.setScreen(new VideoSettingsScreen(this, minecraft, minecraft.options));
		} finally {
			openingVanilla = false;
		}
	}

	private void selectTab(SettingsTab next) {
		if (next == tab) {
			return;
		}
		tab = next;
		lastTab = next;
		rows = next.rows(cfg, vanilla);
		scroll = 0;
		dragged = null;
		resetArmed = false;
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
	public void changed(Binding<?> binding) {
		dirty = true;
		if (binding.isVanilla()) {
			showGpuWarningIfNeeded();
			return;
		}
		String field = binding.id();
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

	/** Vanilla's warning when Improved Transparency is enabled on a GPU with known problems. */
	private void showGpuWarningIfNeeded() {
		if (!gpuWarnings.isShowingWarning()) {
			return;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("options.graphics.warning.message", Component.translatable("options.improvedTransparency").withStyle(ChatFormatting.ITALIC)));
		lines.add(CommonComponents.NEW_LINE);
		addWarning(lines, "options.graphics.warning.renderer", gpuWarnings.getRendererWarnings());
		addWarning(lines, "options.graphics.warning.vendor", gpuWarnings.getVendorWarnings());
		addWarning(lines, "options.graphics.warning.version", gpuWarnings.getVersionWarnings());
		ImmutableList<UnsupportedGraphicsWarningScreen.ButtonOption> buttons = ImmutableList.of(
			new UnsupportedGraphicsWarningScreen.ButtonOption(Component.translatable("options.graphics.warning.accept"), b -> {
				vanilla.options.improvedTransparency().set(true);
				minecraft.levelExtractor.allChanged();
				gpuWarnings.dismissWarning();
				minecraft.gui.setScreen(this);
			}),
			new UnsupportedGraphicsWarningScreen.ButtonOption(Component.translatable("options.graphics.warning.cancel"), b -> {
				gpuWarnings.dismissWarning();
				vanilla.options.improvedTransparency().set(false);
				minecraft.gui.setScreen(this);
			}));
		minecraft.gui.setScreen(new UnsupportedGraphicsWarningScreen(
			Component.translatable("options.graphics.warning.title").withStyle(ChatFormatting.RED), lines, buttons) {});
	}

	private static void addWarning(List<Component> lines, String key, String warning) {
		if (warning != null) {
			lines.add(CommonComponents.NEW_LINE);
			lines.add(Component.translatable(key, warning).withStyle(ChatFormatting.GRAY));
		}
	}

	// --- rendering ---

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		g.fill(0, 0, width, height, UiTheme.BACKDROP);
		renderSidebar(g, mouseX, mouseY);
		renderHeader(g);
		SettingRow hovered = rowAt(mouseX, mouseY);
		renderRows(g, mouseX, mouseY);
		renderDescription(g, hovered, mouseX, mouseY);
		renderFooter(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private void renderSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.fill(0, 0, sidebarW, height, UiTheme.SIDEBAR);
		g.fill(sidebarW, 0, sidebarW + 1, height, UiTheme.BORDER);

		UiTheme.drawLogo(g, 10, 10, 16);
		g.text(font, title, 32, 10, UiTheme.TEXT, false);
		if (!VERSION.isEmpty()) {
			g.text(font, UiTheme.fit(font, "v" + VERSION, sidebarW - 38), 32, 20, UiTheme.TEXT_FAINT, false);
		}

		SettingsTab.Group group = null;
		for (SettingsTab t : SettingsTab.values()) {
			int y = navY(t);
			if (t.group != group) {
				group = t.group;
				String label = Component.translatable(group.titleKey()).getString().toUpperCase(Locale.ROOT);
				g.text(font, UiTheme.fit(font, label, sidebarW - 20), 10, y - 13, UiTheme.TEXT_FAINT, false);
			}
			boolean selected = t == tab;
			boolean hover = !selected && inside(mouseX, mouseY, 6, y, sidebarW - 12, NAV_ITEM_H);
			if (selected) {
				UiTheme.box(g, 6, y, sidebarW - 12, NAV_ITEM_H, UiTheme.SURFACE_HOVER);
				g.fill(6, y + 4, 8, y + NAV_ITEM_H - 4, UiTheme.TEXT);
			} else if (hover) {
				UiTheme.box(g, 6, y, sidebarW - 12, NAV_ITEM_H, UiTheme.SURFACE);
			}
			Component full = Component.translatable(t.titleKey());
			Component label = font.width(full) <= sidebarW - 30 ? full : Component.translatable(t.shortKey());
			g.text(font, label, 14, UiTheme.centerY(font, y, NAV_ITEM_H), selected || hover ? UiTheme.TEXT : UiTheme.TEXT_DIM, false);
		}

		if (showVanillaLink()) {
			int linkY = vanillaLinkY();
			boolean hover = inside(mouseX, mouseY, 6, linkY, sidebarW - 12, NAV_ITEM_H);
			g.fill(10, linkY - 5, sidebarW - 10, linkY - 4, UiTheme.DIVIDER);
			String link = UiTheme.fit(font, Component.translatable("ephemera.ui.vanillaScreen").getString(), sidebarW - 20);
			g.text(font, link, 10, UiTheme.centerY(font, linkY, NAV_ITEM_H), hover ? UiTheme.TEXT : UiTheme.TEXT_FAINT, false);
		}
	}

	private int navY(SettingsTab t) {
		int y = BRAND_H + 16;
		SettingsTab.Group group = SettingsTab.values()[0].group;
		for (SettingsTab other : SettingsTab.values()) {
			if (other.group != group) {
				group = other.group;
				y += 20;
			}
			if (other == t) {
				return y;
			}
			y += NAV_ITEM_H + 1;
		}
		return y;
	}

	private int vanillaLinkY() {
		return height - NAV_ITEM_H - 6;
	}

	/** The vanilla-screen link sits at the sidebar bottom when it does not overlap the page list. */
	private boolean showVanillaLink() {
		SettingsTab[] tabs = SettingsTab.values();
		return vanillaLinkY() > navY(tabs[tabs.length - 1]) + NAV_ITEM_H + 8;
	}

	private void renderHeader(GuiGraphicsExtractor g) {
		int x = rowsX;
		Component pageTitle = Component.translatable(tab.titleKey());
		String backend = VisualsDiagnostics.backendName();
		int chipW = font.width(backend) + 14;
		int chipX = width - PAD - chipW;
		int chipY = headerH >= 38 ? 12 : 6;
		UiTheme.box(g, chipX, chipY, chipW, 14, UiTheme.CHIP);
		g.text(font, backend, chipX + 7, UiTheme.centerY(font, chipY, 14), UiTheme.TEXT, false);
		if (headerH >= 38) {
			g.text(font, pageTitle, x, 10, UiTheme.TEXT, false);
			String subtitle = UiTheme.fit(font, Component.translatable(tab.subtitleKey()).getString(), chipX - x - 8);
			g.text(font, subtitle, x, 22, UiTheme.TEXT_DIM, false);
		} else {
			g.text(font, pageTitle, x, UiTheme.centerY(font, 0, headerH), UiTheme.TEXT, false);
		}
		g.fill(sidebarW + 1, headerH, width, headerH + 1, UiTheme.DIVIDER);
	}

	private void renderRows(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.enableScissor(sidebarW + 1, contentTop, width, contentBottom);
		int y = contentTop - scroll;
		for (SettingRow row : rows) {
			int h = row.height();
			if (y + h > contentTop && y < contentBottom) {
				boolean hovered = dragged == row || dragged == null && isHover(mouseX, mouseY, y, h);
				row.render(this, g, rowsX, y, rowsW, mouseX, mouseY, hovered);
			}
			y += h;
		}
		g.disableScissor();

		int total = totalHeight();
		int view = contentBottom - contentTop;
		if (total > view) {
			int barX = rowsX + rowsW + 4;
			g.fill(barX, contentTop, barX + 2, contentBottom, UiTheme.DIVIDER);
			int thumb = Math.max(16, view * view / total);
			int thumbY = contentTop + (int) ((long) (view - thumb) * scroll / Math.max(1, total - view));
			g.fill(barX, thumbY, barX + 2, thumbY + thumb, UiTheme.TEXT_DIM);
		}
	}

	private void renderDescription(GuiGraphicsExtractor g, SettingRow hovered, int mouseX, int mouseY) {
		Component title = hovered == null ? null : hovered.title();
		Component description = hovered == null ? null : hovered.description();
		if (descW == 0 && descH == 0) {
			if (description != null) {
				g.setTooltipForNextFrame(font, font.split(description, Math.max(120, width / 3)), mouseX, mouseY);
			}
			return;
		}
		boolean side = descW > 0;
		int x = side ? descX : rowsX;
		int y = side ? contentTop : contentBottom + 6;
		int w = side ? descW : rowsW;
		int maxH = side ? contentBottom - contentTop : descH;
		int tw = w - 16;
		int lineH = font.lineHeight + 2;

		Component bodyText = title == null ? Component.translatable("ephemera.ui.hint") : description;
		List<FormattedCharSequence> body = bodyText == null ? List.of() : font.split(bodyText, tw);
		Component defaults = title == null ? null : hovered.defaultText();
		// Title, body, then the default value; the side panel grows to fit, the bottom one is fixed.
		int contentH = (title != null ? font.lineHeight + 5 : 0) + body.size() * lineH + (defaults != null ? lineH + 4 : 0);
		int h = side ? Math.min(maxH, Math.max(40, contentH + 14)) : maxH;
		UiTheme.box(g, x, y, w, h, UiTheme.SURFACE);
		UiTheme.outline(g, x, y, w, h, UiTheme.BORDER);

		int tx = x + 8;
		int ty = y + 8;
		int bottom = y + h - 6;
		if (title != null) {
			g.text(font, UiTheme.fit(font, title.getString(), tw), tx, ty, UiTheme.TEXT, false);
			ty += font.lineHeight + 5;
		}
		int reserved = defaults != null ? lineH : 0;
		int bodyColor = title == null ? UiTheme.TEXT_FAINT : UiTheme.TEXT_DIM;
		for (FormattedCharSequence line : body) {
			if (ty + font.lineHeight > bottom - reserved) {
				break;
			}
			g.text(font, line, tx, ty, bodyColor, false);
			ty += lineH;
		}
		if (defaults != null && ty + font.lineHeight <= bottom) {
			g.text(font, UiTheme.fit(font, defaults.getString(), tw), tx, ty + (body.isEmpty() ? 0 : 4), UiTheme.TEXT_FAINT, false);
		}
	}

	// --- footer ---

	private record FooterButton(int x, int w, Component label, boolean primary, boolean warn, boolean enabled, Runnable action) {}

	private List<FooterButton> footerButtons() {
		Component done = Component.translatable("ephemera.ui.done");
		Component undo = Component.translatable("ephemera.ui.undo");
		Component reset = Component.translatable(resetArmed ? "ephemera.ui.resetConfirm" : "ephemera.ui.resetPage");
		int doneW = Math.max(64, font.width(done) + 20);
		int undoW = Math.max(56, font.width(undo) + 20);
		int resetW = Math.max(64, font.width(reset) + 20);
		int right = width - PAD;
		List<FooterButton> buttons = new ArrayList<>(3);
		buttons.add(new FooterButton(right - doneW, doneW, done, true, false, true, this::onClose));
		right -= doneW + 6;
		boolean undoable = dirty || vanilla.changedSinceOpen();
		buttons.add(new FooterButton(right - undoW, undoW, undo, false, false, undoable, this::onUndo));
		right -= undoW + 6;
		boolean resettable = rows.stream().anyMatch(r -> r instanceof SettingRow.OptionRow<?>);
		buttons.add(new FooterButton(right - resetW, resetW, reset, false, resetArmed, resettable, this::onResetPage));
		return buttons;
	}

	private int footerButtonY() {
		return height - FOOTER_H + (FOOTER_H - BUTTON_H) / 2;
	}

	private void renderFooter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int top = height - FOOTER_H;
		g.fill(sidebarW + 1, top, width, top + 1, UiTheme.DIVIDER);
		int by = footerButtonY();
		int leftmost = width;
		for (FooterButton b : footerButtons()) {
			boolean hover = b.enabled() && inside(mouseX, mouseY, b.x(), by, b.w(), BUTTON_H);
			int fill = b.primary() ? (hover ? UiTheme.ACCENT_HOVER : UiTheme.ACCENT) : (hover ? UiTheme.SURFACE_HOVER : UiTheme.SURFACE);
			UiTheme.box(g, b.x(), by, b.w(), BUTTON_H, fill);
			if (!b.primary()) {
				UiTheme.outline(g, b.x(), by, b.w(), BUTTON_H, b.warn() ? UiTheme.WARN : UiTheme.BORDER);
			}
			int color = !b.enabled() ? UiTheme.TEXT_FAINT : b.primary() ? UiTheme.ON_ACCENT : b.warn() ? UiTheme.WARN : UiTheme.TEXT;
			g.text(font, b.label(), b.x() + (b.w() - font.width(b.label())) / 2, UiTheme.centerY(font, by, BUTTON_H), color, false);
			leftmost = Math.min(leftmost, b.x());
		}

		Component status;
		int color;
		if (vanilla.options.isRestartRequiredToApplyVideoSettings()) {
			status = Component.translatable("options.restartRequired");
			color = UiTheme.WARN;
		} else if (shadersDirty) {
			status = Component.translatable("ephemera.ui.shaderReload");
			color = UiTheme.WARN;
		} else if (dirty) {
			status = Component.translatable("ephemera.ui.unsaved");
			color = UiTheme.TEXT_DIM;
		} else {
			status = null;
			color = 0;
		}
		if (status != null) {
			int sx = sidebarW + PAD;
			String text = UiTheme.fit(font, status.getString(), leftmost - sx - 10);
			g.text(font, text, sx, UiTheme.centerY(font, by, BUTTON_H), color, false);
		}
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
		int max = Math.max(0, totalHeight() - (contentBottom - contentTop));
		scroll = Math.max(0, Math.min(max, scroll));
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private boolean isHover(double mouseX, double mouseY, int y, int h) {
		return mouseX >= rowsX && mouseX < rowsX + rowsW
			&& mouseY >= Math.max(y, contentTop) && mouseY < Math.min(y + h - 2, contentBottom);
	}

	private SettingRow rowAt(double mouseX, double mouseY) {
		int y = contentTop - scroll;
		for (SettingRow row : rows) {
			int h = row.height();
			if (isHover(mouseX, mouseY, y, h)) {
				return row;
			}
			y += h;
		}
		return null;
	}

	private int rowTop(SettingRow target) {
		int y = contentTop - scroll;
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
		boolean wasArmed = resetArmed;
		boolean handled = handleClick(event.x(), event.y(), event.button());
		if (wasArmed) {
			// The click after arming either confirmed Reset Page or cancels it.
			resetArmed = false;
		}
		return handled;
	}

	private boolean handleClick(double mx, double my, int button) {
		if (mx < sidebarW) {
			for (SettingsTab t : SettingsTab.values()) {
				if (inside(mx, my, 6, navY(t), sidebarW - 12, NAV_ITEM_H)) {
					selectTab(t);
					return true;
				}
			}
			if (showVanillaLink() && inside(mx, my, 6, vanillaLinkY(), sidebarW - 12, NAV_ITEM_H)) {
				click();
				openVanilla();
				return true;
			}
			return false;
		}
		if (button == 0) {
			int by = footerButtonY();
			for (FooterButton b : footerButtons()) {
				if (b.enabled() && inside(mx, my, b.x(), by, b.w(), BUTTON_H)) {
					click();
					b.action().run();
					return true;
				}
			}
		}
		SettingRow row = rowAt(mx, my);
		if (row != null && row.mouseClicked(this, rowsX, rowTop(row), rowsW, mx, my, button)) {
			dragged = row;
			click();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragged != null && dragged.mouseDragged(this, rowsX, rowsW, event.x())) {
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragged != null) {
			SettingRow row = dragged;
			dragged = null;
			row.mouseReleased(this);
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= sidebarW && mouseY >= contentTop && mouseY < contentBottom) {
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
		if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
			SettingRow row = rowAt(lastMouseX, lastMouseY);
			if (row != null && row.nudge(this, key == InputConstants.KEY_RIGHT ? 1 : -1)) {
				click();
				return true;
			}
		}
		int page = contentBottom - contentTop;
		switch (key) {
			case InputConstants.KEY_DOWN -> scroll += SettingRow.HEIGHT;
			case InputConstants.KEY_UP -> scroll -= SettingRow.HEIGHT;
			case InputConstants.KEY_PAGEDOWN -> scroll += page;
			case InputConstants.KEY_PAGEUP -> scroll -= page;
			case InputConstants.KEY_HOME -> scroll = 0;
			case InputConstants.KEY_END -> scroll = Integer.MAX_VALUE / 2;
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
