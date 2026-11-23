package com.ephemera.ui;

import com.ephemera.core.config.ConfigStore;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.mixin.client.ui.OptionInstanceAccessor;
import com.ephemera.mixin.client.ui.TooltipAccessor;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Where a settings row's value lives: a public {@link EphemeraConfig} field or a vanilla
 * {@link OptionInstance}. Rows only talk to this, so every control works for both.
 */
abstract class Binding<T> {
	/** Config field name or vanilla option key; identifies the setting in change callbacks. */
	abstract String id();

	abstract Component label();

	/** Hover description for the current value, or null. */
	abstract Component description();

	abstract T get();

	abstract void set(T value);

	abstract T defaultValue();

	/** Short display text for {@code value}, without the option's caption. */
	abstract Component format(T value);

	boolean isVanilla() {
		return false;
	}

	boolean modified() {
		return !Objects.equals(get(), defaultValue());
	}

	// --- Ephemera config field ---

	static final class Config<T> extends Binding<T> {
		private static final EphemeraConfig DEFAULTS = new EphemeraConfig();

		private final EphemeraConfig cfg;
		private final Field field;
		private final Function<T, String> format;
		private final Component label;
		private final Component description;

		Config(EphemeraConfig cfg, String fieldName, Function<T, String> format) {
			this.cfg = cfg;
			try {
				this.field = EphemeraConfig.class.getField(fieldName);
			} catch (NoSuchFieldException e) {
				throw new IllegalArgumentException("Unknown config field: " + fieldName, e);
			}
			this.format = format;
			this.label = Component.translatable("ephemera.option." + fieldName);
			String tooltip = "ephemera.tooltip." + fieldName;
			this.description = Language.getInstance().has(tooltip) ? Component.translatable(tooltip) : null;
		}

		@Override
		String id() {
			return field.getName();
		}

		@Override
		Component label() {
			return label;
		}

		@Override
		Component description() {
			return description;
		}

		@Override
		@SuppressWarnings("unchecked")
		T get() {
			return (T) read(cfg);
		}

		@Override
		void set(T value) {
			try {
				field.set(cfg, value);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			ConfigStore.sanitize(cfg);
		}

		@Override
		@SuppressWarnings("unchecked")
		T defaultValue() {
			return (T) read(DEFAULTS);
		}

		@Override
		Component format(T value) {
			return Component.literal(format.apply(value));
		}

		private Object read(EphemeraConfig source) {
			try {
				return field.get(source);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	// --- vanilla option ---

	static final class Vanilla<T> extends Binding<T> {
		/** Separator left behind after the caption is cut from "Caption: value". */
		private static final String GENERIC_VALUE = "options.generic_value";
		private static final Pattern LEADING_SEPARATOR = Pattern.compile("^[\\s:\\uFF1A\\-\\u2013\\u2014]+");

		final OptionInstance<T> option;
		private final OptionInstanceAccessor<T> access;
		private final String id;

		@SuppressWarnings("unchecked")
		Vanilla(OptionInstance<T> option) {
			this.option = option;
			this.access = (OptionInstanceAccessor<T>) (Object) option;
			this.id = option.toString();
		}

		@Override
		String id() {
			return id;
		}

		@Override
		Component label() {
			return access.ephemera$caption();
		}

		@Override
		Component description() {
			Tooltip tooltip = access.ephemera$tooltip().apply(get());
			return tooltip == null ? null : ((TooltipAccessor) tooltip).ephemera$message();
		}

		@Override
		T get() {
			return option.get();
		}

		@Override
		void set(T value) {
			if (option.values() instanceof OptionInstance.CycleableValueSet<T> cycle && !(option.values() instanceof OptionInstance.SliderableValueSet)) {
				cycle.valueSetter().set(option, value);
			} else {
				option.set(value);
			}
		}

		@Override
		T defaultValue() {
			return access.ephemera$initialValue();
		}

		/**
		 * Vanilla labels read "Caption: value". Takes the value argument of the generic label when
		 * there is one, otherwise cuts a leading caption, including shortened ones such as
		 * "Chunk Fade: 1.00 second(s)" for "Chunk Fade Time".
		 */
		@Override
		Component format(T value) {
			Component full = access.ephemera$toString().apply(value);
			if (full.getContents() instanceof TranslatableContents tc && tc.getKey().equals(GENERIC_VALUE) && tc.getArgs().length == 2) {
				Object arg = tc.getArgs()[1];
				return arg instanceof Component c ? c : Component.literal(String.valueOf(arg));
			}
			String text = full.getString();
			String caption = label().getString();
			if (!caption.isEmpty() && text.startsWith(caption)) {
				String rest = LEADING_SEPARATOR.matcher(text.substring(caption.length())).replaceFirst("");
				if (!rest.isEmpty()) {
					return Component.literal(rest);
				}
			}
			int colon = text.indexOf(": ");
			String firstWord = caption.split(" ", 2)[0];
			if (colon > 0 && !firstWord.isEmpty() && text.startsWith(firstWord)) {
				return Component.literal(text.substring(colon + 2));
			}
			return full;
		}

		@Override
		boolean isVanilla() {
			return true;
		}

		/** Values a cycling control offers, or null when the option is not a fixed list. */
		List<T> choices() {
			if (option.values() instanceof OptionInstance.CycleableValueSet<T> cycle) {
				return cycle.valueListSupplier().getSelectedList();
			}
			return null;
		}
	}
}
