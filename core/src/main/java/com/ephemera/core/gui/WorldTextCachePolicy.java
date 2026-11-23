package com.ephemera.core.gui;

/**
 * Pure-Java hashing, slot-indexing, and frame-TTL policy for Ephemera's render-thread
 * world-space {@code Font.PreparedText} cache (nametags, {@code TextDisplay} holograms, and signs).
 */
public final class WorldTextCachePolicy {
	public static final int SLOT_COUNT = 2048;
	public static final int SLOT_MASK = SLOT_COUNT - 1;
	/** Evict idle entries after 256 frames so transient chat/damage indicators never linger. */
	public static final long MAX_IDLE_FRAMES = 256L;

	private WorldTextCachePolicy() {}

	/**
	 * Computes a well-mixed direct-mapped slot index in {@code [0, SLOT_COUNT)} from the
	 * text-preparation parameters without allocating a key object.
	 */
	public static int slotIndex(
		int textIdentityHash,
		int xBits,
		int yBits,
		int color,
		int backgroundColor,
		int flags
	) {
		int h = textIdentityHash;
		h = mix(h ^ (xBits * 0x9E3779B9));
		h = mix(h ^ (yBits * 0x85EBCA6B));
		h = mix(h ^ (color * 0xC2B2AE35));
		h = mix(h ^ (backgroundColor * 0x27D4EB2F));
		h = mix(h ^ (flags * 0x165667B1));
		return h & SLOT_MASK;
	}

	public static int packFlags(boolean dropShadow, boolean includeEmpty) {
		return (dropShadow ? 1 : 0) | (includeEmpty ? 2 : 0);
	}

	public static boolean isFresh(long currentFrame, long entryFrame) {
		long age = currentFrame - entryFrame;
		return age >= 0L && age <= MAX_IDLE_FRAMES;
	}

	private static int mix(int h) {
		h ^= (h >>> 16);
		h *= 0x7FEB352D;
		h ^= (h >>> 15);
		h *= 0x846CA68B;
		h ^= (h >>> 16);
		return h;
	}
}
