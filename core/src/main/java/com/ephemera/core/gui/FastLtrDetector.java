package com.ephemera.core.gui;

/**
 * Conservative check that a string cannot be changed by the Unicode bidirectional algorithm, so the
 * ICU {@code Bidi} pass can be skipped and the text emitted as a single left-to-right run.
 * <p>
 * Accepted: strong left-to-right letters plus weak/neutral characters (European digits, separators,
 * terminators, whitespace, other neutrals, nonspacing marks, boundary neutrals). With every
 * character at embedding level 0 the visual order equals the logical order.
 * Rejected (falls back to the full algorithm): right-to-left and Arabic letters, Arabic-Indic digits,
 * all explicit embedding/override/isolate controls, paragraph and segment separators (so multi-paragraph
 * text is never judged), and unassigned code points.
 * <p>
 * With a default-RTL paragraph direction the paragraph level comes from the first strong character, so
 * the text must also contain at least one strong left-to-right character to stay at level 0.
 */
public final class FastLtrDetector {
	private static final byte REJECT = 0;
	private static final byte NEUTRAL = 1;
	private static final byte STRONG_LTR = 2;

	private static final byte[] ASCII = new byte[128];

	static {
		for (int c = 0x20; c < 0x7F; c++) {
			ASCII[c] = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ? STRONG_LTR : NEUTRAL;
		}
	}

	private FastLtrDetector() {}

	/**
	 * @param defaultRtl true when the paragraph direction is "default right-to-left" (first strong character decides)
	 * @return true only if the string is guaranteed to reorder to itself under the bidi algorithm
	 */
	public static boolean isPureLtr(CharSequence text, boolean defaultRtl) {
		boolean strongLtr = !defaultRtl;
		int length = text.length();
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (c < 128) {
				byte kind = ASCII[c];
				if (kind == REJECT) {
					return false;
				}
				if (kind == STRONG_LTR) {
					strongLtr = true;
				}
				continue;
			}
			int codePoint = c;
			if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
				codePoint = Character.toCodePoint(c, text.charAt(i + 1));
				i++;
			}
			switch (Character.getDirectionality(codePoint)) {
				case Character.DIRECTIONALITY_LEFT_TO_RIGHT -> strongLtr = true;
				case Character.DIRECTIONALITY_EUROPEAN_NUMBER,
					Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
					Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
					Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
					Character.DIRECTIONALITY_WHITESPACE,
					Character.DIRECTIONALITY_OTHER_NEUTRALS,
					Character.DIRECTIONALITY_NONSPACING_MARK,
					Character.DIRECTIONALITY_BOUNDARY_NEUTRAL -> {
				}
				default -> {
					return false;
				}
			}
		}
		return strongLtr;
	}
}
