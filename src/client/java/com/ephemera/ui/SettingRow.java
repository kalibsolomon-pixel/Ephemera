package com.ephemera.ui;

import com.ephemera.core.config.ConfigStore;
import com.ephemera.core.config.EphemeraConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One line in a settings tab. Config rows bind to a public {@link EphemeraConfig} field by name,
 * so the lang keys are {@code ephemera.option.<field>} / {@code ephemera.tooltip.<field>}.
 */
abstract class SettingRow {
	static final int HEIGHT = 22;
	private static final int CONTROL_WIDTH = 150;

	interface Host {
		Font font();

		/** A config value changed; {@code field} is the config field name. */
		void changed(String field);
	}

	abstract int height();

	abstract void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered);

	boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseDragged(Host host, int x, int w, double mouseX) {
		return false;
	}

	void mouseReleased() {}

	/** Lang key of the hover description, or null. */
	String tooltipKey() {
		return null;
	}

	static String tooltipKeyFor(String id) {
		String key = "ephemera.tooltip." + id;
		return Language.getInstance().has(key) ? key : null;
	}

	static void label(Host host, GuiGraphicsExtractor g, Component text, int x, int y, int color) {
		g.text(host.font(), text, x, y + (HEIGHT - host.font().lineHeight) / 2, color, true);
	}

	// --- section header ---

	static final class Header extends SettingRow {
		private final Component title;

		Header(String key) {
			this.title = Component.translatable(key);
		}

		@Override
		int height() {
			return 20;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			Font font = host.font();
			int ty = y + 8;
			g.text(font, title, x + 2, ty, UiTheme.ACCENT, true);
			int lineX = x + 6 + font.width(title);
			g.fill(lineX, ty + font.lineHeight / 2, x + w, ty + font.lineHeight / 2 + 1, UiTheme.ACCENT_SOFT);
		}
	}

	// --- config-bound rows ---

	abstract static class ConfigRow extends SettingRow {
		final EphemeraConfig cfg;
		final Field field;
		final Component label;
		private final String tooltip;

		ConfigRow(EphemeraConfig cfg, String fieldName) {
			this.cfg = cfg;
			try {
				this.field = EphemeraConfig.class.getField(fieldName);
			} catch (NoSuchFieldException e) {
				throw new IllegalArgumentException("Unknown config field: " + fieldName, e);
			}
			this.label = Component.translatable("ephemera.option." + fieldName);
			this.tooltip = tooltipKeyFor(fieldName);
		}

		@Override
		int height() {
			return HEIGHT;
		}

		@Override
		String tooltipKey() {
			return tooltip;
		}

		void backdrop(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered) {
			g.fill(x, y, x + w, y + HEIGHT - 2, hovered ? UiTheme.ROW_HOVER : UiTheme.ROW);
		}

		boolean overControl(int x, int w, double mouseX) {
			return mouseX >= x + w - CONTROL_WIDTH - 6 && mouseX < x + w - 6;
		}

		Object read() {
			try {
				return field.get(cfg);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}

		void write(Host host, Object value) {
			try {
				field.set(cfg, value);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			ConfigStore.sanitize(cfg);
			host.changed(field.getName());
		}
	}

	static final class Toggle extends ConfigRow {
		Toggle(EphemeraConfig cfg, String fieldName) {
			super(cfg, fieldName);
		}

		private boolean value() {
			return (Boolean) read();
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			backdrop(g, x, y, w, hovered);
			label(host, g, label, x + 8, y, UiTheme.TEXT);
			boolean on = value();
			int trackW = 28;
			int trackH = 12;
			int tx = x + w - trackW - 10;
			int ty = y + (HEIGHT - 2 - trackH) / 2;
			g.fill(tx, ty, tx + trackW, ty + trackH, on ? UiTheme.ACCENT : UiTheme.TRACK);
			int knob = trackH - 4;
			int kx = on ? tx + trackW - knob - 2 : tx + 2;
			g.fill(kx, ty + 2, kx + knob, ty + 2 + knob, on ? 0xFF0E1118 : UiTheme.TEXT);
			Component state = Component.translatable(on ? "ephemera.ui.on" : "ephemera.ui.off");
			g.text(host.font(), state, tx - 8 - host.font().width(state), y + (HEIGHT - host.font().lineHeight) / 2,
				on ? UiTheme.ACCENT : UiTheme.TEXT_DIM, true);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if (button != 0) {
				return false;
			}
			write(host, !value());
			return true;
		}
	}

	/** Int or float slider over a closed range with a fixed step. */
	static final class Slider extends ConfigRow {
		private final double min;
		private final double max;
		private final double step;
		private final Function<Double, String> format;
		private final boolean integer;
		private boolean dragging;

		Slider(EphemeraConfig cfg, String fieldName, double min, double max, double step, Function<Double, String> format) {
			super(cfg, fieldName);
			this.min = min;
			this.max = max;
			this.step = step;
			this.format = format;
			this.integer = field.getType() == int.class;
		}

		private double value() {
			return ((Number) read()).doubleValue();
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			backdrop(g, x, y, w, hovered);
			label(host, g, label, x + 8, y, UiTheme.TEXT);
			int cx = x + w - CONTROL_WIDTH - 6;
			int barY = y + (HEIGHT - 2) / 2 - 2;
			g.fill(cx, barY, cx + CONTROL_WIDTH, barY + 4, UiTheme.TRACK);
			double frac = (value() - min) / (max - min);
			int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, frac)) * CONTROL_WIDTH);
			g.fill(cx, barY, cx + filled, barY + 4, UiTheme.ACCENT);
			int kx = Math.max(cx, Math.min(cx + CONTROL_WIDTH - 3, cx + filled - 1));
			g.fill(kx, barY - 3, kx + 3, barY + 7, dragging || hovered ? 0xFFFFFFFF : UiTheme.TEXT);
			String text = format.apply(value());
			g.text(host.font(), text, cx - 6 - host.font().width(text), y + (HEIGHT - host.font().lineHeight) / 2, UiTheme.TEXT_DIM, true);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if (button != 0 || !overControl(x, w, mouseX)) {
				return false;
			}
			dragging = true;
			setFromMouse(host, x, w, mouseX);
			return true;
		}

		@Override
		boolean mouseDragged(Host host, int x, int w, double mouseX) {
			if (!dragging) {
				return false;
			}
			setFromMouse(host, x, w, mouseX);
			return true;
		}

		@Override
		void mouseReleased() {
			dragging = false;
		}

		private void setFromMouse(Host host, int x, int w, double mouseX) {
			double cx = x + w - CONTROL_WIDTH - 6;
			double frac = Math.max(0.0, Math.min(1.0, (mouseX - cx) / CONTROL_WIDTH));
			double raw = min + frac * (max - min);
			double snapped = Math.round(raw / step) * step;
			snapped = Math.max(min, Math.min(max, snapped));
			Object next;
			if (integer) {
				next = (int) Math.round(snapped);
			} else {
				next = (float) (Math.round(snapped * 1000.0) / 1000.0);
			}
			if (!next.equals(read())) {
				write(host, next);
			}
		}
	}

	/** Cycles through a fixed list of values; left click advances, right click goes back. */
	static final class Choice<T> extends ConfigRow {
		private final List<T> values;
		private final Function<T, String> format;

		Choice(EphemeraConfig cfg, String fieldName, List<T> values, Function<T, String> format) {
			super(cfg, fieldName);
			this.values = values;
			this.format = format;
		}

		@SuppressWarnings("unchecked")
		private int index() {
			Object current = read();
			int idx = values.indexOf((T) current);
			return Math.max(idx, 0);
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			backdrop(g, x, y, w, hovered);
			label(host, g, label, x + 8, y, UiTheme.TEXT);
			String text = format.apply(values.get(index()));
			Font font = host.font();
			int chipW = Math.max(54, font.width(text) + 24);
			int cx = x + w - chipW - 10;
			int cy = y + 2;
			g.fill(cx, cy, cx + chipW, cy + HEIGHT - 6, UiTheme.ACCENT_SOFT);
			g.text(font, "<", cx + 5, cy + (HEIGHT - 6 - font.lineHeight) / 2, UiTheme.TEXT_DIM, true);
			g.text(font, ">", cx + chipW - 5 - font.width(">"), cy + (HEIGHT - 6 - font.lineHeight) / 2, UiTheme.TEXT_DIM, true);
			g.text(font, text, cx + (chipW - font.width(text)) / 2, cy + (HEIGHT - 6 - font.lineHeight) / 2, UiTheme.TEXT, true);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if (button != 0 && button != 1) {
				return false;
			}
			int delta = button == 0 ? 1 : values.size() - 1;
			write(host, values.get((index() + delta) % values.size()));
			return true;
		}
	}

	// --- non-config rows ---

	static final class Action extends SettingRow {
		private final Component label;
		private final Runnable action;
		private final String tooltip;

		Action(String id, Runnable action) {
			this.label = Component.translatable("ephemera.action." + id);
			this.action = action;
			this.tooltip = tooltipKeyFor(id);
		}

		@Override
		int height() {
			return HEIGHT;
		}

		@Override
		String tooltipKey() {
			return tooltip;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			g.fill(x, y, x + w, y + HEIGHT - 2, hovered ? UiTheme.ACCENT_SOFT : UiTheme.ROW);
			UiTheme.frame(g, x, y, w, HEIGHT - 2, hovered ? UiTheme.ACCENT : UiTheme.PANEL_EDGE);
			Font font = host.font();
			g.text(font, label, x + (w - font.width(label)) / 2, y + (HEIGHT - 2 - font.lineHeight) / 2, UiTheme.TEXT, true);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if (button != 0) {
				return false;
			}
			action.run();
			return true;
		}
	}

	/** Live read-only line: label on the left, a colored value on the right. */
	static final class Stat extends SettingRow {
		private final Component label;
		private final Supplier<String> value;
		private final Supplier<Integer> color;

		Stat(String key, Supplier<String> value, Supplier<Integer> color) {
			this.label = Component.translatable(key);
			this.value = value;
			this.color = color;
		}

		@Override
		int height() {
			return 16;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			Font font = host.font();
			int ty = y + (16 - font.lineHeight) / 2;
			g.text(font, label, x + 8, ty, UiTheme.TEXT_DIM, true);
			String text = value.get();
			g.text(font, text, x + w - 8 - font.width(text), ty, color.get(), true);
		}
	}

	static String percent(double v) {
		return String.format(Locale.ROOT, "%.0f%%", v * 100.0);
	}
}
