package com.ephemera.core.gui;

/**
 * Tracks one open underline/strikethrough run and absorbs the next per-glyph decoration quad when it
 * continues the run exactly: same vertical extent, depth, colours and shadow offset, and its left edge
 * equal to the open run's right edge. Touching (not overlapping) quads are merged, so translucent
 * decorations blend identically to the unmerged output.
 * <p>
 * Use one instance per decoration kind (underline, strikethrough) per prepared text.
 */
public final class TextDecorationRunMerger {
	private boolean open;
	private float minX;
	private float minY;
	private float maxX;
	private float maxY;
	private float depth;
	private int color;
	private int shadowColor;
	private float shadowOffset;

	public void reset() {
		open = false;
	}

	/**
	 * @return true if the quad continued the open run (which now spans the combined extent, see
	 *         {@link #minX()}/{@link #maxX()}); false if it started a new run, closing the previous one
	 */
	public boolean extendOrStart(float minX, float minY, float maxX, float maxY, float depth,
								 int color, int shadowColor, float shadowOffset) {
		if (open
			&& minX == this.maxX && maxX >= this.maxX
			&& minY == this.minY && maxY == this.maxY
			&& depth == this.depth
			&& color == this.color && shadowColor == this.shadowColor
			&& shadowOffset == this.shadowOffset) {
			this.maxX = maxX;
			return true;
		}
		open = true;
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
		this.depth = depth;
		this.color = color;
		this.shadowColor = shadowColor;
		this.shadowOffset = shadowOffset;
		return false;
	}

	public float minX() {
		return minX;
	}

	public float minY() {
		return minY;
	}

	public float maxX() {
		return maxX;
	}

	public float maxY() {
		return maxY;
	}

	public float depth() {
		return depth;
	}

	public int color() {
		return color;
	}

	public int shadowColor() {
		return shadowColor;
	}

	public float shadowOffset() {
		return shadowOffset;
	}
}
