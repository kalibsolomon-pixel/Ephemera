package com.ephemera.core.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TextDecorationRunMergerTest {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF3F3F3F;

	private record Quad(float minX, float minY, float maxX, float maxY, float depth, int color, int shadowColor,
						float shadowOffset) {}

	/** Feeds quads through a merger and returns the quads a renderer would emit. */
	private static List<Quad> merge(List<Quad> input) {
		TextDecorationRunMerger merger = new TextDecorationRunMerger();
		List<Quad> out = new ArrayList<>();
		for (Quad q : input) {
			boolean merged = merger.extendOrStart(q.minX, q.minY, q.maxX, q.maxY, q.depth, q.color, q.shadowColor, q.shadowOffset);
			Quad run = new Quad(merger.minX(), merger.minY(), merger.maxX(), merger.maxY(), merger.depth(),
				merger.color(), merger.shadowColor(), merger.shadowOffset());
			if (merged) {
				out.set(out.size() - 1, run);
			} else {
				out.add(run);
			}
		}
		return out;
	}

	private static Quad underline(float x0, float x1) {
		return new Quad(x0, 8f, x1, 9f, 0.01f, WHITE, SHADOW, 1f);
	}

	@Test
	void contiguousGlyphsCollapseToOneQuad() {
		// first glyph extends one pixel left, as vanilla does at string start
		List<Quad> merged = merge(List.of(underline(-1f, 6f), underline(6f, 12f), underline(12f, 17.5f), underline(17.5f, 23f)));
		assertEquals(List.of(underline(-1f, 23f)), merged);
	}

	@Test
	void gapStartsNewRun() {
		List<Quad> merged = merge(List.of(underline(0f, 6f), underline(6f, 12f), underline(18f, 24f)));
		assertEquals(List.of(underline(0f, 12f), underline(18f, 24f)), merged);
	}

	@Test
	void overlapIsNotMerged() {
		List<Quad> merged = merge(List.of(underline(0f, 6f), underline(5f, 12f)));
		assertEquals(2, merged.size());
	}

	@Test
	void anyAttributeChangeBreaksTheRun() {
		Quad base = underline(0f, 6f);
		Quad[] variants = {
			new Quad(6f, 7f, 12f, 9f, 0.01f, WHITE, SHADOW, 1f),
			new Quad(6f, 8f, 12f, 10f, 0.01f, WHITE, SHADOW, 1f),
			new Quad(6f, 8f, 12f, 9f, 0.02f, WHITE, SHADOW, 1f),
			new Quad(6f, 8f, 12f, 9f, 0.01f, 0xFFFF0000, SHADOW, 1f),
			new Quad(6f, 8f, 12f, 9f, 0.01f, WHITE, 0xFF000000, 1f),
			new Quad(6f, 8f, 12f, 9f, 0.01f, WHITE, SHADOW, 0.5f),
		};
		for (Quad variant : variants) {
			assertEquals(List.of(base, variant), merge(List.of(base, variant)), variant.toString());
		}
	}

	@Test
	void backwardsQuadIsNotMerged() {
		List<Quad> merged = merge(List.of(underline(0f, 6f), underline(6f, 3f)));
		assertEquals(2, merged.size());
	}

	@Test
	void resetClosesOpenRun() {
		TextDecorationRunMerger merger = new TextDecorationRunMerger();
		assertFalse(merger.extendOrStart(0f, 8f, 6f, 9f, 0.01f, WHITE, SHADOW, 1f));
		merger.reset();
		assertFalse(merger.extendOrStart(6f, 8f, 12f, 9f, 0.01f, WHITE, SHADOW, 1f));
		assertEquals(6f, merger.minX());
	}

	@Test
	void mergedRunCoversExactlyTheSameAreaAsUnmergedQuads() {
		Random random = new Random(0x5EED5L);
		for (int round = 0; round < 500; round++) {
			List<Quad> input = new ArrayList<>();
			float x = random.nextInt(10) - 5;
			int count = 1 + random.nextInt(30);
			for (int i = 0; i < count; i++) {
				if (random.nextInt(5) == 0) {
					x += 1 + random.nextInt(4); // gap
				}
				float advance = 4 + random.nextInt(4) + (random.nextBoolean() ? 0.5f : 0f);
				int color = random.nextInt(3) == 0 ? 0xFFFF0000 : WHITE;
				input.add(new Quad(x, 8f, x + advance, 9f, 0.01f, color, SHADOW, 1f));
				x += advance;
			}
			List<Quad> merged = merge(input);
			assertTrue(merged.size() <= input.size());
			// same coverage: every input quad lies inside exactly one merged quad of the same colour
			float inputArea = 0;
			float mergedArea = 0;
			for (Quad q : input) {
				inputArea += q.maxX - q.minX;
				boolean covered = merged.stream().anyMatch(m -> m.color == q.color && m.minX <= q.minX && m.maxX >= q.maxX);
				assertTrue(covered, q.toString());
			}
			for (Quad m : merged) {
				mergedArea += m.maxX - m.minX;
			}
			assertEquals(inputArea, mergedArea, 0.01f);
		}
	}
}
