package com.ephemera.core.render;

/** Four recent single-color framebuffer lookups. Zero is a miss. */
public final class FramebufferLookup {
	private static final int CAPACITY = 4;
	private final int[] entries = new int[CAPACITY * 5];
	private int next;

	public int find(int color, int colorMip, int depth, int depthMip) {
		for (int i = 0; i < entries.length; i += 5) {
			if (entries[i] != 0 && entries[i + 1] == color && entries[i + 2] == colorMip
					&& entries[i + 3] == depth && entries[i + 4] == depthMip) {
				return entries[i];
			}
		}
		return 0;
	}

	public void remember(int color, int colorMip, int depth, int depthMip, int framebuffer) {
		int i = next * 5;
		entries[i] = framebuffer;
		entries[i + 1] = color;
		entries[i + 2] = colorMip;
		entries[i + 3] = depth;
		entries[i + 4] = depthMip;
		next = (next + 1) % CAPACITY;
	}

	public void remove(int framebuffer) {
		for (int i = 0; i < entries.length; i += 5) {
			if (entries[i] == framebuffer) {
				entries[i] = 0;
			}
		}
	}
}
