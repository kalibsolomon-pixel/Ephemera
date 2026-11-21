package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.GlyphGeometry;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BakedSheetGlyph.class)
public interface BakedSheetGlyphAccess extends GlyphGeometry {
	@Accessor("u0") float ephemera$u0();
	@Accessor("u1") float ephemera$u1();
	@Accessor("v0") float ephemera$v0();
	@Accessor("v1") float ephemera$v1();
	@Accessor("left") float ephemera$left();
	@Accessor("right") float ephemera$right();
	@Accessor("up") float ephemera$up();
	@Accessor("down") float ephemera$down();
}
