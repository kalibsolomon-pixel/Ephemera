package com.ephemera.render.gui;

import com.ephemera.core.gui.FastLtrDetector;
import it.unimi.dsi.fastutil.ints.Int2IntFunction;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.SubStringSource;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.UnaryOperator;

/**
 * Pure left-to-right shortcut for {@code FormattedBidiReorder.reorder}. For text that cannot reorder,
 * vanilla's ICU {@code Bidi} always yields a single forward run covering the whole string, so the same
 * sequence is produced directly from the styled sub-string source.
 */
public final class FastBidi {
	private static final Int2IntFunction NO_MIRROR = codePoint -> codePoint;
	private static final UnaryOperator<String> NO_SHAPING = UnaryOperator.identity();

	private FastBidi() {}

	/** @return the reordered sequence, or null when the text needs the full bidi algorithm */
	public static FormattedCharSequence tryReorder(FormattedText text, boolean defaultRtl) {
		String plain = text.getString();
		if (plain.isEmpty() || !FastLtrDetector.isPureLtr(plain, defaultRtl)) {
			return null;
		}
		SubStringSource source = SubStringSource.create(text, NO_MIRROR, NO_SHAPING);
		String sourceText = source.getPlainText();
		if (sourceText.length() != plain.length()) {
			return null;
		}
		return FormattedCharSequence.composite(source.substring(0, sourceText.length(), false));
	}
}
