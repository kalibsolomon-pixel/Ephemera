package com.ephemera.render.gui;

import com.ephemera.core.gui.WorldTextCachePolicy;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

/**
 * Zero-allocation direct-mapped render-thread cache for world-space {@link Font.PreparedText}
 * instances (entity nametags, {@code TextDisplay} holograms, and sign lines).
 *
 * <p>In congested multiplayer hubs with 50-300 holograms, player nametags, and signs, vanilla
 * rebuilds {@code PreparedTextBuilder}, runs {@code StringDecomposer}, resolves every glyph from
 * {@code FontSet}, and allocates per-character {@code GlyphInstance} objects on every frame.
 * Caching immutable prepared text by exact identity and style parameters eliminates both that CPU
 * work and hundreds of MB/s of render-thread GC pressure.
 */
public final class WorldPreparedTextCache {
	private static final int SLOTS = WorldTextCachePolicy.SLOT_COUNT;

	private static final Font[] FONTS = new Font[SLOTS];
	private static final FormattedCharSequence[] SEQS = new FormattedCharSequence[SLOTS];
	private static final int[] X_BITS = new int[SLOTS];
	private static final int[] Y_BITS = new int[SLOTS];
	private static final int[] COLORS = new int[SLOTS];
	private static final int[] BG_COLORS = new int[SLOTS];
	private static final byte[] FLAGS = new byte[SLOTS];
	private static final long[] ENTRY_GEN = new long[SLOTS];
	private static final long[] ENTRY_FRAME = new long[SLOTS];
	private static final Font.PreparedText[] PREPARED = new Font.PreparedText[SLOTS];

	private static long generation = 1L;
	private static long frameCounter = 1L;

	private WorldPreparedTextCache() {}

	public static void beginFrame() {
		frameCounter++;
	}

	/** Clears all cached glyph lists on resource reload, font reload, or world switch. */
	public static void invalidate() {
		generation++;
		Arrays.fill(FONTS, null);
		Arrays.fill(SEQS, null);
		Arrays.fill(PREPARED, null);
	}

	public static Font.PreparedText lookup(
		Font font,
		FormattedCharSequence seq,
		float x,
		float y,
		int color,
		boolean dropShadow,
		boolean includeEmpty,
		int backgroundColor
	) {
		if (font == null || seq == null || !GuiFeatures.get().fastLtrText || !RenderSystem.isOnRenderThread()) {
			return null;
		}
		int xBits = Float.floatToRawIntBits(x);
		int yBits = Float.floatToRawIntBits(y);
		int flags = WorldTextCachePolicy.packFlags(dropShadow, includeEmpty);
		int slot = WorldTextCachePolicy.slotIndex(System.identityHashCode(seq), xBits, yBits, color, backgroundColor, flags);
		if (ENTRY_GEN[slot] == generation
			&& FONTS[slot] == font
			&& SEQS[slot] == seq
			&& X_BITS[slot] == xBits
			&& Y_BITS[slot] == yBits
			&& COLORS[slot] == color
			&& BG_COLORS[slot] == backgroundColor
			&& FLAGS[slot] == (byte) flags
			&& WorldTextCachePolicy.isFresh(frameCounter, ENTRY_FRAME[slot])) {
			ENTRY_FRAME[slot] = frameCounter;
			return PREPARED[slot];
		}
		return null;
	}

	public static void store(
		Font font,
		FormattedCharSequence seq,
		float x,
		float y,
		int color,
		boolean dropShadow,
		boolean includeEmpty,
		int backgroundColor,
		Font.PreparedText prepared
	) {
		if (font == null || seq == null || prepared == null
			|| !GuiFeatures.get().fastLtrText
			|| !RenderSystem.isOnRenderThread()
			|| !(prepared instanceof PreparedTextCacheable cacheable)
			|| !cacheable.ephemera$isCacheable()) {
			return;
		}
		int xBits = Float.floatToRawIntBits(x);
		int yBits = Float.floatToRawIntBits(y);
		int flags = WorldTextCachePolicy.packFlags(dropShadow, includeEmpty);
		int slot = WorldTextCachePolicy.slotIndex(System.identityHashCode(seq), xBits, yBits, color, backgroundColor, flags);
		FONTS[slot] = font;
		SEQS[slot] = seq;
		X_BITS[slot] = xBits;
		Y_BITS[slot] = yBits;
		COLORS[slot] = color;
		BG_COLORS[slot] = backgroundColor;
		FLAGS[slot] = (byte) flags;
		ENTRY_GEN[slot] = generation;
		ENTRY_FRAME[slot] = frameCounter;
		PREPARED[slot] = prepared;
	}
}
