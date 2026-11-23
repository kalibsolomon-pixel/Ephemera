package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.WorldPreparedTextCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caches immutable {@link Font.PreparedText} instances for HUD/GUI text lines (scoreboard sidebar,
 * bossbars, actionbar, chat lines, tab list, and overlays) across frames so congested multiplayer
 * servers do not re-decompose and re-allocate every HUD glyph on every frame.
 */
@Mixin(GuiTextRenderState.class)
abstract class GuiTextRenderStateCacheMixin {
	@WrapOperation(
		method = "ensurePrepared",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"
		)
	)
	private Font.PreparedText ephemera$cachePreparedGuiText(
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
