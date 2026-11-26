package com.ephemera.core.culling;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionOpacityCacheTest {
	/** Fake section: per-cell opacity grid plus a missing/all-air flag. */
	private static final class FakeSection {
		final boolean[] opaque = new boolean[16 * 16 * 16];
		boolean allAir;

		FakeSection allAir() {
			this.allAir = true;
			return this;
		}

		FakeSection opaque(int lx, int ly, int lz) {
			opaque[cell(lx, ly, lz)] = true;
			return this;
		}

		private static int cell(int lx, int ly, int lz) {
			return (ly & 15) << 8 | (lz & 15) << 4 | lx & 15;
		}
	}

	private int loaderCalls;
	private final Map<String, FakeSection> world = new HashMap<>();

	private final SectionOpacityCache<Void, FakeSection> cache = new SectionOpacityCache<>(
		new SectionOpacityCache.Loader<Void, FakeSection>() {
			@Override
			public FakeSection load(Void context, int sx, int sy, int sz, int x, int y, int z) {
				loaderCalls++;
				return world.get(sx + "," + sy + "," + sz);
			}

			@Override
			public boolean missingOrAir(Void context, FakeSection section) {
				return section == null || section.allAir;
			}

			@Override
			public boolean isOpaque(Void context, FakeSection section, int x, int y, int z) {
				return section.opaque[FakeSection.cell(x & 15, y & 15, z & 15)];
			}
		});

	/** Replicates the cache's slot hash to place colliding sections in tests. */
	private static int slot(int sx, int sy, int sz) {
		int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
		return (h ^ (h >>> 16)) & 255;
	}

	private static final long STAMP = 42L;
	private static final long FRAME = 7L;

	@Test void slotCollisionHelperFindsRecyclePartner() {
		// Sanity: the test's replicated hash must produce a usable collision partner for (0,0,0).
		int base = slot(0, 0, 0);
		int[] partner = findCollisionPartner(0, 0, 0);
		assertEquals(base, slot(partner[0], partner[1], partner[2]));
		assertNotEquals(0, partner[0] + partner[1] + partner[2]);
	}

	private int[] findCollisionPartner(int sx, int sy, int sz) {
		int base = slot(sx, sy, sz);
		for (int x = 1; x < 512; x++) {
			for (int y = -2; y < 4; y++) {
				int z = 0;
				if (slot(x, y, z) == base && (x != sx || y != sy || z != sz)) {
					return new int[]{x, y, z};
				}
			}
		}
		throw new AssertionError("no collision partner found");
	}

	@Test void recycledSlotNeverResurrectsStaleOpaqueBitsAirPath() {
		// Section A: all 64 cells of word 0 opaque. Probe two so the word is valid + opaque.
		world.put("0,0,0", new FakeSection()
			.opaque(0, 0, 0).opaque(1, 0, 0).opaque(2, 0, 0).opaque(3, 0, 0)
			.opaque(4, 0, 0).opaque(5, 0, 0).opaque(6, 0, 0).opaque(7, 0, 0));
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 0, 0, 0));
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 1, 0, 0));

		// Recycle the same slot with an all-air section: the first touch must clear the whole
		// word, so no cell of the word can answer from A's stale known/opaque bits.
		int[] p = findCollisionPartner(0, 0, 0);
		world.put(p[0] + "," + p[1] + "," + p[2], new FakeSection().allAir());
		int baseCalls = loaderCalls;
		for (int lx = 0; lx < 8; lx++) {
			assertFalse(cache.isOpaque(null, STAMP, FRAME, p[0] * 16 + lx, p[1] * 16, p[2] * 16),
				"stale opaque bit resurrected for cell " + lx);
		}
		assertEquals(baseCalls + 1, loaderCalls,
			"one world load for the recycled slot; the rest answer from the fresh section");
	}

	@Test void recycledSlotNeverResurrectsStaleKnownBitsMixedPath() {
		// Section A: cell 0 opaque, cells 1..7 left unprobed but word marked valid.
		world.put("0,0,0", new FakeSection().opaque(0, 0, 0));
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 0, 0, 0));

		// Recycle with a mixed section whose cell 0 is NOT opaque.
		int[] p = findCollisionPartner(0, 0, 0);
		FakeSection mixed = new FakeSection().opaque(2, 0, 0);
		world.put(p[0] + "," + p[1] + "," + p[2], mixed);

		assertFalse(cache.isOpaque(null, STAMP, FRAME, p[0] * 16, p[1] * 16, p[2] * 16),
			"stale known bit said opaque for a transparent cell");
		assertTrue(cache.isOpaque(null, STAMP, FRAME, p[0] * 16 + 2, p[1] * 16, p[2] * 16),
			"real opaque cell in recycled slot must stay opaque");
		assertFalse(cache.isOpaque(null, STAMP, FRAME, p[0] * 16 + 3, p[1] * 16, p[2] * 16),
			"unprobed transparent cell must consult the world, not stale bits");
	}

	@Test void secondWordOfSameSectionDoesNotWipeTheFirst() {
		// word 0 = cell (x&15=0,z&15=0); word 1 = (x&15=0,z&15=4) -> cell index 64.
		world.put("1,0,0", new FakeSection().opaque(0, 0, 0).opaque(0, 0, 4));
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0), "word 0 cell");
		int afterFirstWord = loaderCalls;
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 4), "word 1 cell");
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0), "word 0 cell again");
		assertEquals(afterFirstWord, loaderCalls,
			"cross-word probe must keep the section and the first word's cache");
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 4), "word 1 cell still cached");
		assertEquals(afterFirstWord, loaderCalls);
	}

	@Test void invalidateSectionForcesFreshWorldReads() {
		FakeSection section = new FakeSection().opaque(0, 0, 0);
		world.put("1,0,0", section);
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0));
		// Terrain changed: the cell is no longer opaque.
		section.opaque[0] = false;
		cache.invalidateSection(1, 0, 0);
		assertFalse(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0));
		assertTrue(loaderCalls >= 2, "invalidated section must be re-read, not answered stale");
	}

	@Test void invalidateColumnAndClearAndStampBumpDiscardState() {
		world.put("1,0,0", new FakeSection().opaque(0, 0, 0));
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0));

		// Column invalidation + unload: the next read must consult the world afresh.
		world.remove("1,0,0");
		cache.invalidateColumn(1, 0);
		assertFalse(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0), "column invalidation cleared");

		// Full clear: a different section in the slot must be read fresh.
		world.put("1,0,0", new FakeSection().opaque(1, 0, 0));
		cache.clear();
		assertFalse(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0), "clear discarded everything");
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 17, 0, 0), "fresh read after clear");

		// Stamp bump: the slot recycles, so replaced world data must be visible.
		world.clear();
		world.put("1,0,0", new FakeSection().allAir());
		assertFalse(cache.isOpaque(null, STAMP + 1, FRAME, 17, 0, 0), "stamp bump recycles slot");
	}

	@Test void releaseSectionsKeepsValidBitsButRefetchesSectionOnNewProbes() {
		world.put("1,0,0", new FakeSection().opaque(0, 0, 0));
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0));
		int before = loaderCalls;
		cache.releaseSections();
		// Same cell: word-valid + bit-known answers from the cache without touching the world.
		assertTrue(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0));
		assertEquals(before, loaderCalls);
		// A different cell of the same word: bit unknown, section reference released -> refetch.
		assertFalse(cache.isOpaque(null, STAMP, FRAME, 16, 0, 1));
		assertEquals(before + 1, loaderCalls);
	}

	@Test void unloadedChunksFailOpenWithoutPoisoningTheCache() {
		int before = loaderCalls;
		assertFalse(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0), "unloaded chunk fails open");
		assertTrue(loaderCalls > before);
		// The per-cell answer persists across frames by design (bits survive section release);
		// repeat probes of the same cell stay cached until the slot is invalidated or recycled.
		assertFalse(cache.isOpaque(null, STAMP, FRAME, 16, 0, 0));
		assertFalse(cache.isOpaque(null, STAMP, FRAME + 1, 16, 0, 0));
		assertEquals(before + 1, loaderCalls);
	}
}
