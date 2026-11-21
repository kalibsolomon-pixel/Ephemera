package com.ephemera.core.gui;

import org.junit.jupiter.api.Test;

import java.text.Bidi;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FastLtrDetectorTest {
	private static final String[] POOL = {
		"a", "b", "Z", "m", "\u00E9", "\u00DF", "\u0436", "\u4E2D", "\u65E5", "\uD55C", "\u0E01", "0", "1", "9", " ", " ", ".", ",", ":", "-", "+",
		"%", "$", "(", ")", "\u00A7", "#", "/", "!", "\u00AD", "\u200D", "\u0301", "\uD83D\uDE00", "\uD835\uDC9C",
		"\u05D0", "\u05D1", "\u05E9", "\u0639", "\u0628", "\u064A", "\u0663", "\u0660", "\u06F5", "\u200F", "\u200E", "\u202B", "\u202E", "\u202C",
		"\u2066", "\u2067", "\u2069", "\n", "\t", "\u2029", "\u0085", "\uFEFF", "\uD800", "\uDC00",
		"\u0600", "\u05D0\u05B0", "\uFB1D", "\uFE70", "\u2028",
	};

	private static String randomString(Random random) {
		int length = random.nextInt(24);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < length; i++) {
			builder.append(POOL[random.nextInt(POOL.length)]);
		}
		return builder.toString();
	}

	/** True when the JDK bidi implementation leaves the text as one level-0 left-to-right run. */
	private static boolean referenceKeepsOrder(String text, boolean defaultRtl) {
		Bidi bidi = new Bidi(text, defaultRtl ? Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT : Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
		return bidi.isLeftToRight();
	}

	@Test
	void acceptsPlainLatinText() {
		assertTrue(FastLtrDetector.isPureLtr("Hello, World! 123", false));
		assertTrue(FastLtrDetector.isPureLtr("Hello, World! 123", true));
		assertTrue(FastLtrDetector.isPureLtr("Diamond Sword (Sharpness V)", true));
		assertTrue(FastLtrDetector.isPureLtr("\u65E5\u672C\u8A9E\u306E\u30C6\u30AD\u30B9\u30C8", true));
	}

	@Test
	void ltrDefaultAcceptsStrongFreeText() {
		assertTrue(FastLtrDetector.isPureLtr("", false));
		assertTrue(FastLtrDetector.isPureLtr("123 + 456", false));
		assertTrue(FastLtrDetector.isPureLtr("-- ..", false));
	}

	@Test
	void rtlDefaultNeedsAStrongLtrCharacter() {
		assertFalse(FastLtrDetector.isPureLtr("", true));
		assertFalse(FastLtrDetector.isPureLtr("123 + 456", true));
		assertFalse(FastLtrDetector.isPureLtr("-- ..", true));
		assertTrue(FastLtrDetector.isPureLtr("123 x", true));
	}

	@Test
	void rejectsRightToLeftScripts() {
		assertFalse(FastLtrDetector.isPureLtr("abc \u05D0\u05D1\u05D2", false));
		assertFalse(FastLtrDetector.isPureLtr("abc \u0627\u0644\u0639\u0631\u0628\u064A\u0629", false));
		assertFalse(FastLtrDetector.isPureLtr("\uFB1D", false));
		assertFalse(FastLtrDetector.isPureLtr("\uFE70", false));
	}

	@Test
	void rejectsArabicIndicDigits() {
		assertFalse(FastLtrDetector.isPureLtr("abc \u0663", false));
		assertFalse(FastLtrDetector.isPureLtr("\u0660\u0661", false));
	}

	@Test
	void rejectsBidiControlCharacters() {
		for (char control : new char[] {'\u200F', '\u061C', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
			'\u2066', '\u2067', '\u2068', '\u2069'}) {
			assertFalse(FastLtrDetector.isPureLtr("a" + control + "b", false), "U+" + Integer.toHexString(control));
		}
	}

	@Test
	void rejectsParagraphAndSegmentSeparatorsAndControls() {
		assertFalse(FastLtrDetector.isPureLtr("a\nb", false));
		assertFalse(FastLtrDetector.isPureLtr("a\tb", false));
		assertFalse(FastLtrDetector.isPureLtr("a\u2029b", false));
		assertFalse(FastLtrDetector.isPureLtr("a\u0000b", false));
		assertFalse(FastLtrDetector.isPureLtr("a\u007Fb", false));
	}

	@Test
	void handlesSurrogatePairs() {
		assertTrue(FastLtrDetector.isPureLtr("a\uD83D\uDE00b", true));
		// supplementary Adlam letters are right-to-left
		assertFalse(FastLtrDetector.isPureLtr("a\uD83A\uDD00", false));
	}

	@Test
	void everyBmpCodePointAcceptedMatchesReferenceBidi() {
		int accepted = 0;
		for (int cp = 0; cp <= 0xFFFF; cp++) {
			if (cp >= 0xD800 && cp <= 0xDFFF) {
				continue;
			}
			String text = "a" + new String(Character.toChars(cp)) + "b";
			for (boolean defaultRtl : new boolean[] {false, true}) {
				if (FastLtrDetector.isPureLtr(text, defaultRtl)) {
					accepted++;
					assertTrue(referenceKeepsOrder(text, defaultRtl),
						"detector accepted U+" + Integer.toHexString(cp) + " defaultRtl=" + defaultRtl);
				}
			}
		}
		assertTrue(accepted > 50_000, "detector should accept the bulk of the BMP, accepted " + accepted);
	}

	@Test
	void fiveThousandRandomStringsNeverAcceptedWhenReferenceReorders() {
		Random random = new Random(0x17A5E7L);
		int accepted = 0;
		int total = 12_000;
		for (int i = 0; i < total; i++) {
			String text = randomString(random);
			for (boolean defaultRtl : new boolean[] {false, true}) {
				boolean detector = FastLtrDetector.isPureLtr(text, defaultRtl);
				if (detector) {
					accepted++;
					assertTrue(referenceKeepsOrder(text, defaultRtl),
						"detector accepted but reference reorders: " + escape(text) + " defaultRtl=" + defaultRtl);
				}
			}
		}
		assertTrue(accepted > 500, "fuzz should exercise the accepting path, accepted " + accepted);
	}

	@Test
	void asciiStringsAreExactlyAcceptedWhenPrintable() {
		Random random = new Random(0xA5C11L);
		for (int i = 0; i < 6_000; i++) {
			int length = random.nextInt(40);
			StringBuilder builder = new StringBuilder();
			boolean hasLetter = false;
			for (int j = 0; j < length; j++) {
				char c = (char) (0x20 + random.nextInt(0x5F));
				hasLetter |= (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
				builder.append(c);
			}
			String text = builder.toString();
			assertTrue(FastLtrDetector.isPureLtr(text, false), escape(text));
			assertEquals(hasLetter, FastLtrDetector.isPureLtr(text, true), escape(text));
			assertTrue(referenceKeepsOrder(text, false), escape(text));
			if (hasLetter) {
				assertTrue(referenceKeepsOrder(text, true), escape(text));
			}
		}
	}

	private static String escape(String text) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				builder.append(String.format("\\u%04X", (int) c));
			} else {
				builder.append(c);
			}
		}
		return builder.toString();
	}
}
