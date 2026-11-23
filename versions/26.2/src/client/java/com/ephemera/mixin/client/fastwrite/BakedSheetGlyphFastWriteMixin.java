package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastGlyphWriter;
import com.ephemera.render.fastwrite.GlyphGeometry;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BakedSheetGlyph.class)
abstract class BakedSheetGlyphFastWriteMixin {
	@Inject(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$writeGlyph(boolean italic, float x, float y, float z, Matrix4fc matrix, VertexConsumer consumer,
			int color, boolean bold, int light, CallbackInfo ci) {
		if (this instanceof GlyphGeometry glyph
				&& FastGlyphWriter.writeGlyph(consumer, matrix, italic, bold, x, y, z,
					glyph.ephemera$left(), glyph.ephemera$right(), glyph.ephemera$up(), glyph.ephemera$down(),
					glyph.ephemera$u0(), glyph.ephemera$u1(), glyph.ephemera$v0(), glyph.ephemera$v1(), color, light)) {
			ci.cancel();
		}
	}

	@Inject(method = "buildEffect", at = @At("HEAD"), cancellable = true)
	private void ephemera$writeEffect(@Coerce Object effect, float offset, float depth, int color, VertexConsumer consumer,
			int light, Matrix4fc matrix, CallbackInfo ci) {
		if (this instanceof GlyphGeometry glyph && effect instanceof GlyphGeometry.Effect bounds
				&& FastGlyphWriter.writeEffect(consumer, matrix, offset, depth,
					bounds.ephemera$x0(), bounds.ephemera$y0(), bounds.ephemera$x1(), bounds.ephemera$y1(),
					glyph.ephemera$u0(), glyph.ephemera$u1(), glyph.ephemera$v0(), glyph.ephemera$v1(), color, light)) {
			ci.cancel();
		}
	}
}
