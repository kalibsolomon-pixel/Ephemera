package com.ephemera.render.gui;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;

/**
 * Immutable snapshot of the GUI/text/item and unfocused-FPS settings so mixin hot paths read plain
 * final fields instead of re-validating config values. {@link #init()} is called at client startup
 * and whenever settings are edited or reverted; font atlas dimensions take effect on the next
 * resource reload (F3+T) or restart as new glyph pages are allocated.
 */
public final class GuiFeatures {
	private static volatile GuiFeatures current;

	public final boolean layerIndexing;
	public final boolean fastLtrText;
	public final boolean mergeTextDecorations;
	public final boolean fontAtlasResizing;
	public final int fontAtlasSize;
	public final boolean animatedItemBaking;
	public final boolean glintRateLimit;
	public final long glintPeriodNanos;
	public final boolean skipTextSortWork;
	public final boolean itemStateCache;
	public final boolean unfocusedFpsLimit;
	public final int unfocusedFps;

	private GuiFeatures(EphemeraConfig cfg) {
		layerIndexing = cfg.guiLayerIndexing;
		fastLtrText = cfg.guiFastLtrText;
		mergeTextDecorations = cfg.guiMergeTextDecorations;
		fontAtlasResizing = cfg.guiFontAtlasResizing;
		fontAtlasSize = Integer.highestOneBit(Math.clamp(cfg.guiFontAtlasSize, 256, 4096));
		animatedItemBaking = cfg.guiAnimatedItemBaking;
		glintRateLimit = cfg.guiGlintRateLimit;
		glintPeriodNanos = 1_000_000_000L / Math.clamp(cfg.guiGlintFps, 1, 1000);
		skipTextSortWork = cfg.guiSkipTextTranslucencySort;
		itemStateCache = cfg.guiAnimatedItemBaking || cfg.guiHudFrameCache;
		unfocusedFpsLimit = cfg.unfocusedFpsLimitEnabled;
		unfocusedFps = Math.clamp(cfg.unfocusedFpsLimit, 1, 260);
	}

	public static void init() {
		init(EphemeraCommon.config());
	}

	static void init(EphemeraConfig cfg) {
		current = new GuiFeatures(cfg);
	}

	public static GuiFeatures get() {
		GuiFeatures features = current;
		if (features == null) {
			init();
			features = current;
		}
		return features;
	}
}
