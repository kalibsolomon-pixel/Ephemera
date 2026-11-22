package com.ephemera.render.terrain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Change counters the chunk batcher uses to decide whether last frame's regional plan is
 * still exact. Every event that can change which section layers are drawable, where their
 * geometry lives, or which sections are visible bumps a counter; the batcher only reuses a
 * plan when no counter moved since it was built (plus camera, fog, culling and section-list
 * identity checks of its own).
 *
 * <ul>
 *   <li>{@link #topology()}: allocation mirrored or freed, mesh closed, section mesh
 *   swapped, dispatcher disposed. Bumped from whichever thread performs the change.</li>
 *   <li>{@link #visibleSections()}: {@code LevelRenderer.visibleSections} cleared (every
 *   frustum application clears it before refilling). Render thread only.</li>
 * </ul>
 */
public final class TerrainTopology {
	private static final AtomicLong TOPOLOGY = new AtomicLong();
	private static final AtomicLong DIRTY_SECTIONS = new AtomicLong(1L);
	private static volatile long visibleSections;

	private TerrainTopology() {}

	public static long topology() {
		return TOPOLOGY.get();
	}

	public static void bumpTopology() {
		TOPOLOGY.incrementAndGet();
	}

	public static long visibleSections() {
		return visibleSections;
	}

	public static void bumpVisibleSections() {
		visibleSections++;
	}

	public static long dirtySections() {
		return DIRTY_SECTIONS.get();
	}

	public static void bumpDirtySections() {
		DIRTY_SECTIONS.incrementAndGet();
	}
}
