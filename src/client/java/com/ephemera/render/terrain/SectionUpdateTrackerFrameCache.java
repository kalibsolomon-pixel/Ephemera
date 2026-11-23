package com.ephemera.render.terrain;

/**
 * Single-frame column-level neighbor-readiness cache on {@code SectionUpdateTracker}, active only
 * during {@code LevelExtractor.extract}'s visible-section scan so vertical sections in the same
 * chunk column share one 8-neighbor chunk + light readiness probe instead of repeating it up to
 * 24 times per frame.
 */
public interface SectionUpdateTrackerFrameCache {
	void ephemera$beginExtractFrame();

	void ephemera$endExtractFrame();
}
