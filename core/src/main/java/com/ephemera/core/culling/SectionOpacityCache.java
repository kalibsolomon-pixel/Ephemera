package com.ephemera.core.culling;

import java.util.Arrays;

/**
 * 256-slot exact section keys + lazy per-word opacity bitsets persisted across frames while the
 * stamp is unchanged. MC-free; the world reads live behind {@link Loader} (mirrors
 * {@link ParticleCellOcclusion}'s solidity seam) so the bookkeeping is unit-testable.
 *
 * <p>Validity is tracked per 64-cell <em>word</em>. A recycled or invalidated slot leaves the
 * previous section's word bytes in place but marks every word invalid, and the first probe of
 * each word clears both word arrays before validating the probed bit — stale bits from a
 * recycled section can never read as known/opaque. The word check lives inside the
 * section-match branch: probing a second word of the same section costs one word clear, never
 * a slot wipe, so a slot holds up to all four words of its section at once.
 */
public final class SectionOpacityCache<C, S> {
	/** World seam: load one section and evaluate cell opacity against it. */
	public interface Loader<C, S> {
		/** Loads the live section containing cell (x, y, z), or null when unloaded. */
		S load(C context, int sx, int sy, int sz, int x, int y, int z);

		/** True when the section is absent or entirely air (every cell known not-opaque). */
		boolean missingOrAir(C context, S section);

		/** Exact opacity predicate for one cell; must fail open (false) for transparent/dynamic states. */
		boolean isOpaque(C context, S section, int x, int y, int z);
	}

	private static final int SLOTS = 256;
	private static final int MASK = SLOTS - 1;

	private final Loader<C, S> loader;
	private final S[] sections = (S[]) new Object[SLOTS];
	private final int[] xs = new int[SLOTS], ys = new int[SLOTS], zs = new int[SLOTS];
	private final long[] stamps = new long[SLOTS];
	private final long[] sectionFrames = new long[SLOTS];
	private final long[][] known = new long[SLOTS][64], opaque = new long[SLOTS][64];
	// Per-slot 64-bit validity mask over the known/opaque words (wave 6).
	private final long[] knownWordMasks = new long[SLOTS];
	private boolean hasSectionRefs;

	public SectionOpacityCache(Loader<C, S> loader) {
		if (loader == null) throw new IllegalArgumentException("loader required");
		this.loader = loader;
	}

	/** Releases section references at frame end without discarding valid opacity bitsets. */
	public void releaseSections() {
		if (!hasSectionRefs) {
			return;
		}
		hasSectionRefs = false;
		Arrays.fill(sections, null);
		Arrays.fill(sectionFrames, 0L);
	}

	/** Full invalidation on terrain revision or world change. */
	public void clear() {
		hasSectionRefs = false;
		Arrays.fill(sections, null);
		Arrays.fill(sectionFrames, 0L);
		Arrays.fill(stamps, 0L);
	}

	/** Targeted invalidation of one 16x16x16 section after a block opacity change. */
	public void invalidateSection(int sx, int sy, int sz) {
		int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
		int slot = (h ^ (h >>> 16)) & MASK;
		if (stamps[slot] != 0L && xs[slot] == sx && ys[slot] == sy && zs[slot] == sz) {
			stamps[slot] = 0L;
			sectionFrames[slot] = 0L;
			sections[slot] = null;
			knownWordMasks[slot] = 0L;
		}
	}

	/** Targeted invalidation of one chunk column after chunk load or unload. */
	public void invalidateColumn(int sx, int sz) {
		for (int i = 0; i < SLOTS; i++) {
			if (stamps[i] != 0L && xs[i] == sx && zs[i] == sz) {
				stamps[i] = 0L;
				sectionFrames[i] = 0L;
				sections[i] = null;
				knownWordMasks[i] = 0L;
			}
		}
	}

	public boolean isOpaque(C context, long stamp, long frame, int x, int y, int z) {
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
		int slot = (h ^ (h >>> 16)) & MASK;
		int cell = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
		int word = cell >>> 6;
		long bit = 1L << cell;
		if (stamps[slot] != stamp || xs[slot] != sx || ys[slot] != sy || zs[slot] != sz) {
			xs[slot] = sx;
			ys[slot] = sy;
			zs[slot] = sz;
			stamps[slot] = stamp;
			sectionFrames[slot] = 0L;
			sections[slot] = null;
			// Recycled slot: every word holds the previous section's data. Leave the stale bytes
			// in place and mark all words invalid - each word is cleared before it is re-validated.
			knownWordMasks[slot] = 0L;
		}
		// Not else-if: a recycled slot falls into this check so its first touch clears the word.
		if ((knownWordMasks[slot] & (1L << word)) == 0L) {
			// First touch of this word since recycle/invalidate: the stale bytes of the previous
			// section must never read as known - clear both word arrays before validating the bit.
			known[slot][word] = 0L;
			opaque[slot][word] = 0L;
			knownWordMasks[slot] |= 1L << word;
		} else if ((known[slot][word] & bit) != 0L) {
			return (opaque[slot][word] & bit) != 0L;
		}
		S section;
		if (sectionFrames[slot] == frame) {
			section = sections[slot];
		} else {
			section = loader.load(context, sx, sy, sz, x, y, z);
			sections[slot] = section;
			sectionFrames[slot] = frame;
			hasSectionRefs = true;
		}
		boolean opaqueHere;
		if (loader.missingOrAir(context, section)) {
			opaqueHere = false;
		} else {
			opaqueHere = loader.isOpaque(context, section, x, y, z);
		}
		knownWordMasks[slot] |= 1L << word;
		known[slot][word] |= bit;
		if (opaqueHere) opaque[slot][word] |= bit;
		else opaque[slot][word] &= ~bit;
		return opaqueHere;
	}
}
