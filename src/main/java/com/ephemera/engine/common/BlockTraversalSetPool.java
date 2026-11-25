package com.ephemera.engine.common;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Re-entrancy-safe, zero-allocation ThreadLocal pool of tiny {@link LongOpenHashSet} instances for
 * {@code BlockGetter.forEachBlockIntersectedBetween} (~84 MB on Server thread in mob/survival ticks).
 * Uses a primitive bitmask to track which call frames borrowed the pooled set across early returns
 * and nested invocations without allocating wrapper objects.
 */
public final class BlockTraversalSetPool {
	private static final int MAX_RETAINED_SIZE = 256;

	private static final class Slot {
		final LongOpenHashSet set = new LongOpenHashSet(16);
		boolean inUse;
		long borrowMask;
	}

	private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

	private BlockTraversalSetPool() {}

	public static void enterFrame() {
		SLOT.get().borrowMask <<= 1;
	}

	public static LongOpenHashSet borrow() {
		Slot s = SLOT.get();
		if (!s.inUse) {
			s.inUse = true;
			s.borrowMask |= 1L;
			s.set.clear();
			return s.set;
		}
		return new LongOpenHashSet(16);
	}

	public static void exitFrame() {
		Slot s = SLOT.get();
		if ((s.borrowMask & 1L) != 0L) {
			if (s.set.size() > MAX_RETAINED_SIZE) {
				s.set.clear();
				s.set.trim(16);
			} else {
				s.set.clear();
			}
			s.inUse = false;
		}
		s.borrowMask >>>= 1;
	}
}
