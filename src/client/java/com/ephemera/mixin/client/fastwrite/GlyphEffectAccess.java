package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.GlyphGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$EffectInstance")
public interface GlyphEffectAccess extends GlyphGeometry.Effect {
	@Accessor("x0") float ephemera$x0();
	@Accessor("x1") float ephemera$x1();
	@Accessor("y0") float ephemera$y0();
	@Accessor("y1") float ephemera$y1();
}
