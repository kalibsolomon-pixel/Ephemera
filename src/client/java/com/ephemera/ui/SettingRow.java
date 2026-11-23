package com.ephemera.ui;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One line on a settings page. Option rows edit a {@link Binding}, so the same switch, slider and
 * choice controls serve both Ephemera config fields and vanilla options.
 */
abstract class SettingRow {
	/** Option row height, including the 2px gap below it. */
	static final int HEIGHT = 24;
	static final int BOX_H = HEIGHT - 2;

	interface Host {
		Font font();

		/** The user changed {@code binding}'s value. */
		void changed(Binding<?> binding);
	}

	private BooleanSupplier enabled = () -> true;

	abstract int height();

	abstract void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered);

	boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseDragged(Host host, int x, int w, double mouseX) {
		return false;
	}

	void mouseReleased(Host host) {}

	/** Arrow-key adjustment while hovered; {@code dir} is -1 or +1. */
	boolean nudge(Host host, int dir) {
		return false;
	}

	/** Title of the description panel, or null when the row has nothing to describe. */
	Component title() {
		return null;
	}

	Component description() {
		return null;
	}

	/** "Default: ..." text for the description panel, or null. */
	Component defaultText() {
		return null;
	}

	final SettingRow enabledWhen(BooleanSupplier condition) {
		this.enabled = condition;
		return this;
	}

	final boolean enabled() {
		return enabled.getAsBoolean();
	}

	boolean interactive() {
		return false;
	}

	/** Width of the control column on the right of an option row. */
	static int controlWidth(int rowW) {
		return Math.max(96, Math.min(170, rowW * 2 / 5));
	}

	// --- section header ---

	static final class Header extends SettingRow {
		private final Component title;

		Header(String key) {
			this.title = Component.translatable(key);
		}

		@Override
		int height() {
			return 22;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			Font font = host.font();
			int ty = y + 22 - font.lineHeight - 4;
			String text = title.getString().toUpperCase(Locale.ROOT);
			g.text(font, text, x + 2, ty, UiTheme.TEXT_DIM, false);
			int lineX = x + 8 + font.width(text);
			if (lineX < x + w) {
				g.fill(lineX, ty + font.lineHeight / 2 - 1, x + w, ty + font.lineHeight / 2, UiTheme.DIVIDER);
			}
		}
	}

	// --- option rows ---

	abstract static class OptionRow<T> extends SettingRow {
		final Binding<T> binding;

		OptionRow(Binding<T> binding) {
			this.binding = binding;
		}

		@Override
		int height() {
			return HEIGHT;
		}

		@Override
		boolean interactive() {
			return enabled();
		}

		@Override
		Component title() {
			return binding.label();
		}

		@Override
		Component description() {
			return binding.description();
		}

		@Override
		Component defaultText() {
			return Component.translatable("ephemera.ui.default", binding.format(binding.defaultValue()));
		}

		void set(Host host, T value) {
			if (!Objects.equals(value, binding.get())) {
				binding.set(value);
				host.changed(binding);
			}
		}

		/**
		 * Row backdrop, hover bar, modified dot, and the label, which is cut short so it ends before
		 * {@code valueW} pixels of value text left of the control.
		 */
		void frame(Host host, GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, int valueW) {
			boolean on = enabled();
			UiTheme.box(g, x, y, w, BOX_H, hovered && on ? UiTheme.SURFACE_HOVER : UiTheme.SURFACE);
			if (hovered && on) {
				g.fill(x, y + 4, x + 2, y + BOX_H - 4, UiTheme.TEXT_DIM);
			}
			if (binding.modified()) {
				g.fill(x + 5, y + BOX_H / 2 - 1, x + 7, y + BOX_H / 2 + 1, on ? UiTheme.TEXT : UiTheme.TEXT_FAINT);
			}
			Font font = host.font();
			int labelW = controlX(x, w) - x - 11 - 8 - (valueW > 0 ? valueW + 8 : 0);
			String text = UiTheme.fit(font, binding.label().getString(), labelW);
			g.text(font, text, x + 11, UiTheme.centerY(font, y, BOX_H), on ? UiTheme.TEXT : UiTheme.TEXT_FAINT, false);
		}

		static int controlX(int x, int w) {
			return x + w - controlWidth(w) - 8;
		}

		/** Value text drawn right-aligned at {@code rightEdge}, keeping at least half the label room. */
		static void drawValue(Host host, GuiGraphicsExtractor g, int x, int y, int rightEdge, String value, int color) {
			Font font = host.font();
			String text = UiTheme.fit(font, value, (rightEdge - x - 11) / 2);
			g.text(font, text, rightEdge - font.width(text), UiTheme.centerY(font, y, BOX_H), color, false);
		}

		static int valueWidth(Font font, String value, int x, int rightEdge) {
			return Math.min(font.width(value), (rightEdge - x - 11) / 2);
		}
	}

	static final class Toggle extends OptionRow<Boolean> {
		private static final int TRACK_W = 22;
		private static final int TRACK_H = 12;

		Toggle(Binding<Boolean> binding) {
			super(binding);
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			boolean on = Boolean.TRUE.equals(binding.get());
			boolean active = enabled();
			Font font = host.font();
			Component state = Component.translatable(on ? "ephemera.ui.on" : "ephemera.ui.off");
			int tx = x + w - TRACK_W - 8;
			frame(host, g, x, y, w, hovered, Math.max(0, font.width(state) - (tx - 6 - controlX(x, w))));
			int ty = y + (BOX_H - TRACK_H) / 2;
			int track = !active ? UiTheme.TRACK : on ? (hovered ? UiTheme.ACCENT_HOVER : UiTheme.ACCENT) : UiTheme.TRACK;
			UiTheme.box(g, tx, ty, TRACK_W, TRACK_H, track);
			int knob = TRACK_H - 4;
			int kx = on ? tx + TRACK_W - knob - 2 : tx + 2;
			UiTheme.box(g, kx, ty + 2, knob, knob, !active ? UiTheme.TEXT_FAINT : on ? UiTheme.ON_ACCENT : UiTheme.KNOB);
			g.text(font, state, tx - 6 - font.width(state), UiTheme.centerY(font, y, BOX_H),
				!active ? UiTheme.TEXT_FAINT : on ? UiTheme.TEXT : UiTheme.TEXT_DIM, false);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if (button != 0 || !enabled()) {
				return false;
			}
			set(host, !Boolean.TRUE.equals(binding.get()));
			return true;
		}

		@Override
		boolean nudge(Host host, int dir) {
			if (!enabled()) {
				return false;
			}
			set(host, dir > 0);
			return true;
		}
	}

	/** Maps a binding's values onto the 0..1 slider track. */
	interface SliderModel<T> {
		double toFraction(T value);

		T fromFraction(double fraction);

		/** Number of discrete stops, or 0 when there are too many to mark. */
		int stops();

		T step(T value, int dir);

		/** Ephemera config number field over a closed range with a fixed step. */
		static SliderModel<Number> range(double min, double max, double step, boolean integer) {
			return new SliderModel<>() {
				@Override
				public double toFraction(Number value) {
					return (value.doubleValue() - min) / (max - min);
				}

				@Override
				public Number fromFraction(double fraction) {
					return snap(min + fraction * (max - min));
				}

				@Override
				public int stops() {
					long stops = Math.round((max - min) / step) + 1;
					return stops <= 17 ? (int) stops : 0;
				}

				@Override
				public Number step(Number value, int dir) {
					return snap(value.doubleValue() + dir * step);
				}

				private Number snap(double raw) {
					double snapped = Math.max(min, Math.min(max, min + Math.round((raw - min) / step) * step));
					return integer ? (Number) (int) Math.round(snapped) : (Number) (float) (Math.round(snapped * 1000.0) / 1000.0);
				}
			};
		}

		/** A vanilla sliderable value set, including GUI Scale's lazily bounded range. */
		static <T> SliderModel<T> vanilla(OptionInstance.SliderableValueSet<T> values) {
			return new SliderModel<>() {
				@Override
				public double toFraction(T value) {
					return values.toSliderValue(value);
				}

				@Override
				public T fromFraction(double fraction) {
					return values.fromSliderValue(fraction);
				}

				@Override
				public int stops() {
					if (values instanceof OptionInstance.IntRangeBase range) {
						int stops = range.maxInclusive() - range.minInclusive() + 1;
						return stops <= 17 ? stops : 0;
					}
					return 0;
				}

				@Override
				public T step(T value, int dir) {
					return (dir > 0 ? values.next(value) : values.previous(value)).orElse(value);
				}
			};
		}
	}

	static final class Slider<T> extends OptionRow<T> {
		private final SliderModel<T> model;
		private final boolean deferred;
		private boolean dragging;
		private T pending;

		Slider(Binding<T> binding, SliderModel<T> model, boolean deferred) {
			super(binding);
			this.model = model;
			this.deferred = deferred;
		}

		private T shown() {
			return pending != null ? pending : binding.get();
		}

		private static int trackX(int x, int w) {
			return controlX(x, w) + 3;
		}

		private static int trackW(int w) {
			return controlWidth(w) - 6;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			boolean active = enabled();
			Font font = host.font();
			T value = shown();
			String valueText = binding.format(value).getString();
			int valueRight = controlX(x, w) - 8;
			frame(host, g, x, y, w, hovered, valueWidth(font, valueText, x, valueRight));
			drawValue(host, g, x, y, valueRight, valueText,
				!active ? UiTheme.TEXT_FAINT : dragging ? UiTheme.TEXT : UiTheme.TEXT_DIM);
			int tx = trackX(x, w);
			int tw = trackW(w);
			int cy = y + BOX_H / 2;
			double frac = Math.max(0.0, Math.min(1.0, model.toFraction(value)));
			int filled = (int) Math.round(frac * tw);
			UiTheme.box(g, tx, cy - 2, tw, 4, UiTheme.TRACK);
			if (filled > 0) {
				UiTheme.box(g, tx, cy - 2, Math.max(3, filled), 4, active ? UiTheme.SLIDER_FILL : UiTheme.TICK);
			}
			int stops = model.stops();
			if (stops > 2) {
				for (int i = 0; i < stops; i++) {
					int sx = tx + (int) Math.round((double) i * (tw - 1) / (stops - 1));
					if (sx > tx + filled) {
						g.fill(sx, cy - 1, sx + 1, cy + 1, UiTheme.TICK);
					}
				}
			}
			int kx = Math.max(tx, Math.min(tx + tw - 6, tx + filled - 3));
			UiTheme.box(g, kx, cy - 5, 6, 10, !active ? UiTheme.TEXT_FAINT : dragging || hovered ? 0xFFFFFFFF : UiTheme.KNOB);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if (button != 0 || !enabled() || mouseX < controlX(x, w) - 2) {
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
		void mouseReleased(Host host) {
			dragging = false;
			if (pending != null) {
				T value = pending;
				pending = null;
				set(host, value);
			}
		}

		@Override
		boolean nudge(Host host, int dir) {
			if (!enabled()) {
				return false;
			}
			set(host, model.step(binding.get(), dir));
			return true;
		}

		private void setFromMouse(Host host, int x, int w, double mouseX) {
			double frac = Math.max(0.0, Math.min(1.0, (mouseX - trackX(x, w)) / trackW(w)));
			T next = model.fromFraction(frac);
			if (deferred) {
				pending = next;
			} else {
				set(host, next);
			}
		}
	}

	/**
	 * Picks one of a short list. Up to three values that fit show as a segmented control; longer
	 * lists cycle (left click forward, right click back).
	 */
	static final class Choice<T> extends OptionRow<T> {
		private final Supplier<List<T>> values;

		Choice(Binding<T> binding, Supplier<List<T>> values) {
			super(binding);
			this.values = values;
		}

		Choice(Binding<T> binding, List<T> values) {
			this(binding, () -> values);
		}

		private boolean segmented(Font font, List<T> list, int cw) {
			if (list.size() > 3) {
				return false;
			}
			int seg = cw / list.size();
			for (T v : list) {
				if (font.width(binding.format(v)) + 8 > seg) {
					return false;
				}
			}
			return true;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			frame(host, g, x, y, w, hovered, 0);
			Font font = host.font();
			List<T> list = values.get();
			boolean active = enabled();
			int cx = controlX(x, w);
			int cw = controlWidth(w);
			int cy = y + 3;
			int ch = BOX_H - 6;
			T current = binding.get();
			UiTheme.box(g, cx, cy, cw, ch, UiTheme.SURFACE_SUNKEN);
			if (segmented(font, list, cw)) {
				int seg = cw / list.size();
				for (int i = 0; i < list.size(); i++) {
					T v = list.get(i);
					int sx = cx + i * seg;
					int sw = i == list.size() - 1 ? cw - i * seg : seg;
					boolean selected = v.equals(current);
					boolean over = active && hovered && mouseX >= sx && mouseX < sx + sw && mouseY >= cy && mouseY < cy + ch;
					if (selected) {
						UiTheme.box(g, sx + 1, cy + 1, sw - 2, ch - 2, active ? UiTheme.ACCENT : UiTheme.TICK);
					} else if (over) {
						UiTheme.box(g, sx + 1, cy + 1, sw - 2, ch - 2, UiTheme.SURFACE_HOVER);
					}
					Component label = binding.format(v);
					int color = !active ? UiTheme.TEXT_FAINT : selected ? UiTheme.ON_ACCENT : over ? UiTheme.TEXT : UiTheme.TEXT_DIM;
					g.text(font, label, sx + (sw - font.width(label)) / 2, UiTheme.centerY(font, cy, ch), color, false);
				}
				return;
			}
			int arrow = active && hovered ? UiTheme.TEXT : UiTheme.TEXT_FAINT;
			int ty = UiTheme.centerY(font, cy, ch);
			g.text(font, "<", cx + 5, ty, arrow, false);
			g.text(font, ">", cx + cw - 5 - font.width(">"), ty, arrow, false);
			String text = UiTheme.fit(font, binding.format(current).getString(), cw - 26);
			g.text(font, text, cx + (cw - font.width(text)) / 2, ty, active ? UiTheme.TEXT : UiTheme.TEXT_FAINT, false);
		}

		@Override
		boolean mouseClicked(Host host, int x, int y, int w, double mouseX, double mouseY, int button) {
			if ((button != 0 && button != 1) || !enabled()) {
				return false;
			}
			List<T> list = values.get();
			int cx = controlX(x, w);
			int cw = controlWidth(w);
			if (button == 0 && segmented(host.font(), list, cw) && mouseX >= cx && mouseX < cx + cw) {
				int idx = Math.min(list.size() - 1, (int) ((mouseX - cx) / (cw / list.size())));
				set(host, list.get(idx));
				return true;
			}
			return nudge(host, button == 0 ? 1 : -1);
		}

		@Override
		boolean nudge(Host host, int dir) {
			if (!enabled()) {
				return false;
			}
			List<T> list = values.get();
			int idx = Math.max(0, list.indexOf(binding.get()));
			set(host, list.get(Math.floorMod(idx + dir, list.size())));
			return true;
		}
	}

	// --- non-option rows ---

	static final class Action extends SettingRow {
		private final Component label;
		private final Component description;
		private final Runnable action;

		Action(String id, Runnable action) {
			this.label = Component.translatable("ephemera.action." + id);
			String tooltip = "ephemera.tooltip." + id;
			this.description = Language.getInstance().has(tooltip) ? Component.translatable(tooltip) : null;
			this.action = action;
		}

		@Override
		int height() {
			return HEIGHT;
		}

		@Override
		boolean interactive() {
			return true;
		}

		@Override
		Component title() {
			return label;
		}

		@Override
		Component description() {
			return description;
		}

		@Override
		void render(Host host, GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, boolean hovered) {
			UiTheme.box(g, x, y, w, BOX_H, hovered ? UiTheme.SURFACE_HOVER : UiTheme.SURFACE);
			UiTheme.outline(g, x, y, w, BOX_H, hovered ? UiTheme.TEXT_DIM : UiTheme.BORDER);
			Font font = host.font();
			g.text(font, label, x + (w - font.width(label)) / 2, UiTheme.centerY(font, y, BOX_H), UiTheme.TEXT, false);
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
			int ty = UiTheme.centerY(font, y, 16);
			g.fill(x, y + 15, x + w, y + 16, UiTheme.DIVIDER);
			g.text(font, label, x + 4, ty, UiTheme.TEXT_DIM, false);
			int labelW = font.width(label) + 16;
			String text = UiTheme.fit(font, value.get(), w - labelW - 8);
			g.text(font, text, x + w - 4 - font.width(text), ty, color.get(), false);
		}
	}
}
