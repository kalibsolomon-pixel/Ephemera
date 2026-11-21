package com.ephemera.mixin.client.compat;

import com.ephemera.render.fastwrite.FastGlyphWriter;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Caches pre-inset SDF texture coordinates on Aurora Client's {@code SdfAtlas$Cell} record
 * (compatible with both 5-field Aurora 3.6.0 cells and 7-field Aurora 3.7.0+ cells, including
 * resized font atlases).
 */
@Pseudo
@Mixin(targets = "com.aurora.client.ui.text.SdfAtlas$Cell", remap = false)
public abstract class AuroraSdfCellMixin implements FastGlyphWriter.SdfCell {
	@Shadow(remap = false) @Final private FontTexture page;
	@Shadow(remap = false) @Final private int x;
	@Shadow(remap = false) @Final private int y;
	@Shadow(remap = false) @Final private int width;
	@Shadow(remap = false) @Final private int height;

	@Unique private boolean ephemera$uvInit;
	@Unique private float ephemera$u0;
	@Unique private float ephemera$u1;
	@Unique private float ephemera$v0;
	@Unique private float ephemera$v1;

	@Unique
	private void ephemera$ensureUv() {
		if (!this.ephemera$uvInit) {
			int pw = 256;
			int ph = 256;
			if (this.page != null) {
				var tex = this.page.getTexture();
				if (tex != null) {
					pw = Math.max(1, tex.getWidth(0));
					ph = Math.max(1, tex.getHeight(0));
				}
			}
			this.ephemera$u0 = Math.clamp((this.x + 0.5f) / (float) pw, 0.0f, 1.0f);
			this.ephemera$u1 = Math.clamp((this.x + this.width - 0.5f) / (float) pw, 0.0f, 1.0f);
			this.ephemera$v0 = Math.clamp((this.y + 0.5f) / (float) ph, 0.0f, 1.0f);
			this.ephemera$v1 = Math.clamp((this.y + this.height - 0.5f) / (float) ph, 0.0f, 1.0f);
			this.ephemera$uvInit = true;
		}
	}

	@Override
	public float ephemera$u0() {
		ephemera$ensureUv();
		return this.ephemera$u0;
	}

	@Override
	public float ephemera$u1() {
		ephemera$ensureUv();
		return this.ephemera$u1;
	}

	@Override
	public float ephemera$v0() {
		ephemera$ensureUv();
		return this.ephemera$v0;
	}

	@Override
	public float ephemera$v1() {
		ephemera$ensureUv();
		return this.ephemera$v1;
	}
}
