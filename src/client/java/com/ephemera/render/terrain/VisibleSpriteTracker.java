package com.ephemera.render.terrain;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.Util;

import java.util.Arrays;
import java.util.List;

/**
 * Visible-only animated texture updates for the block atlas.
 *
 * <p>Each animated block-atlas sprite gets a dense id when the atlas uploads. The section
 * compiler records which ids a section's block models reference; every frame the chunk
 * batcher stamps the ids of the sections it actually draws, and the item, block-model and
 * moving-block feature renderers stamp the sprites of every quad they emit. An animation state then
 * skips its GPU blit while its sprite is unstamped, and redraws once it becomes visible.
 *
 * <p>The policy is deliberately conservative. A sprite is always animated when:
 * <ul>
 *   <li>it was never seen in a compiled section's block models (fluids, entity-only use,
 *   or anything the compiler could not enumerate),</li>
 *   <li>it belongs to a fluid model,</li>
 *   <li>a visible section contains a non-vanilla model whose sprites are unknown,</li>
 *   <li>the batcher has not run recently (so nothing is stamping), or</li>
 *   <li>the feature is disabled.</li>
 * </ul>
 * Stamps are wall-clock milliseconds compared against a short grace window, so a sprite is
 * at most one animation tick stale when it scrolls back into view.
 */
public final class VisibleSpriteTracker {
	/** How long a stamp keeps a sprite animating. */
	private static final long GRACE_MS = 200L;
	/** If the batcher has not stamped for this long, animate everything. */
	private static final long INACTIVE_MS = 250L;
	private static final long NEVER = Long.MIN_VALUE / 4;

	/** Per-atlas-upload state; replaced atomically so compile threads see a consistent set. */
	static final class Generation {
		final int id;
		final int count;
		final boolean[] seenInTerrain;
		final boolean[] neverSkip;
		final long[] lastVisible;
		final long[] lastExternal;
		volatile boolean fluidsScanned;

		Generation(int id, int count) {
			this.id = id;
			this.count = count;
			this.seenInTerrain = new boolean[count];
			this.neverSkip = new boolean[count];
			this.lastVisible = new long[count];
			this.lastExternal = new long[count];
			Arrays.fill(lastVisible, NEVER);
			Arrays.fill(lastExternal, NEVER);
		}
	}

	private static volatile Generation current = new Generation(0, 0);
	private static int nextGenerationId = 1;

	// Render-thread state.
	private static volatile Thread renderThread;
	private static long lastBatchMillis = NEVER;
	private static long lastUntrackedVisibleMillis = NEVER;
	private static boolean trackingFrame;

	private VisibleSpriteTracker() {}

	static Generation generation() {
		return current;
	}

	/** Called on the render thread after the block atlas uploaded its sprites. */
	public static void onBlockAtlasUpload(List<TextureAtlasSprite> sprites) {
		int count = 0;
		for (TextureAtlasSprite sprite : sprites) {
			SpriteContents contents = sprite.contents();
			if (contents instanceof AnimatedSpriteHandle handle) {
				handle.ephemera$setAnimId(contents.isAnimated() ? count++ : -1);
			}
		}
		current = new Generation(nextGenerationId++, count);
	}

	/** Called when the block atlas drops its texture data; every id becomes unknown. */
	public static void onBlockAtlasCleared() {
		current = new Generation(nextGenerationId++, 0);
	}

	static int idOf(TextureAtlasSprite sprite) {
		return sprite.contents() instanceof AnimatedSpriteHandle handle ? handle.ephemera$animId() : -1;
	}

	/** Batcher frame start: arms tracking for this frame when the feature is enabled. */
	static void beginFrame(long now) {
		renderThread = Thread.currentThread();
		trackingFrame = TerrainFeatures.animateOnlyVisible();
		if (trackingFrame) {
			lastBatchMillis = now;
		}
	}

	static boolean trackingFrame() {
		return trackingFrame;
	}

	/** Stamps the sprites of a section that is drawn this frame. */
	static void markSection(SectionMeshRenderCache cache, long now) {
		Generation g = current;
		if (cache.ephemera$untrackedSprites() || cache.ephemera$spriteGeneration() != g.id) {
			lastUntrackedVisibleMillis = now;
			return;
		}
		int[] ids = cache.ephemera$animatedSprites();
		long[] visible = g.lastVisible;
		for (int id : ids) {
			if (id < visible.length) {
				visible[id] = now;
			}
		}
	}

	/** Stamps the sprite of a quad emitted by a feature renderer (items, block models, moving blocks). */
	public static void markExternal(BakedQuad quad) {
		if (Thread.currentThread() != renderThread) {
			return;
		}
		int id = idOf(quad.materialInfo().sprite());
		if (id >= 0) {
			Generation g = current;
			if (id < g.count) {
				g.lastExternal[id] = lastBatchMillis;
			}
		}
	}

	/** Decides whether an animation state for sprite {@code id} should upload this tick. */
	public static boolean shouldAnimate(int id) {
		if (id < 0 || !TerrainFeatures.animateOnlyVisible()) {
			return true;
		}
		Generation g = current;
		if (id >= g.count || !g.seenInTerrain[id] || g.neverSkip[id]) {
			return true;
		}
		long now = Util.getMillis();
		if (now - lastBatchMillis > INACTIVE_MS || now - lastUntrackedVisibleMillis < GRACE_MS) {
			return true;
		}
		return now - g.lastVisible[id] < GRACE_MS || now - g.lastExternal[id] < GRACE_MS;
	}
}
