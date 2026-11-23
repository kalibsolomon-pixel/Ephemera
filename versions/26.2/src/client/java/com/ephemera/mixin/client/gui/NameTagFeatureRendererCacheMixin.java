package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.WorldPreparedTextCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caches immutable {@link Font.PreparedText} instances for entity/player nametags across frames so
 * crowded multiplayer hubs skip repeated {@code StringDecomposer} + {@code FontSet} glyph lookups
 * and per-character {@code GlyphInstance} heap allocations.
 */
@Mixin(NameTagFeatureRenderer.class)
abstract class NameTagFeatureRendererCacheMixin {
	@WrapOperation(
		method = "prepareText",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"
		)
	)
	private static Font.PreparedText ephemera$cachePreparedNameTag(
		Font font, FormattedCharSequence seq, float x, float y, int color,
		boolean dropShadow, boolean includeEmpty, int backgroundColor,
		Operation<Font.PreparedText> original
	) {
		Font.PreparedText cached = WorldPreparedTextCache.lookup(
			font, seq, x, y, color, dropShadow, includeEmpty, backgroundColor);
		if (cached != null) {
			return cached;
		}
		Font.PreparedText built = original.call(font, seq, x, y, color, dropShadow, includeEmpty, backgroundColor);
		WorldPreparedTextCache.store(font, seq, x, y, color, dropShadow, includeEmpty, backgroundColor, built);
		return built;
	}
}
