package com.ephemera.mixin.client.gui;

import com.ephemera.core.gui.TextDecorationRunMerger;
import com.ephemera.render.gui.GuiFeatures;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Vanilla emits one underline/strikethrough quad per glyph. Contiguous quads with identical attributes are
 * collapsed into one quad that replaces the previous one in the effect list.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
abstract class FontPreparedTextBuilderMixin {
	private static final String ACCEPT = "accept(ILnet/minecraft/network/chat/Style;Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;)Z";
	private static final String CREATE_EFFECT = "Lnet/minecraft/client/gui/font/glyphs/EffectGlyph;createEffect(FFFFFIIF)Lnet/minecraft/client/gui/font/TextRenderable;";

	@Shadow private List<TextRenderable> effects;

	@Unique private final TextDecorationRunMerger ephemera$strikethrough = new TextDecorationRunMerger();
	@Unique private final TextDecorationRunMerger ephemera$underline = new TextDecorationRunMerger();
	@Unique private TextRenderable ephemera$lastStrikethrough;
	@Unique private TextRenderable ephemera$lastUnderline;

	@WrapOperation(method = ACCEPT, at = @At(value = "INVOKE", target = CREATE_EFFECT, ordinal = 0))
	private TextRenderable ephemera$mergeStrikethrough(
		EffectGlyph glyph, float minX, float minY, float maxX, float maxY, float depth,
		int color, int shadowColor, float shadowOffset, Operation<TextRenderable> original
	) {
		TextRenderable effect = ephemera$decorate(ephemera$strikethrough, ephemera$lastStrikethrough, glyph,
			minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset, original);
		ephemera$lastStrikethrough = effect;
		return effect;
	}

	@WrapOperation(method = ACCEPT, at = @At(value = "INVOKE", target = CREATE_EFFECT, ordinal = 1))
	private TextRenderable ephemera$mergeUnderline(
		EffectGlyph glyph, float minX, float minY, float maxX, float maxY, float depth,
		int color, int shadowColor, float shadowOffset, Operation<TextRenderable> original
	) {
		TextRenderable effect = ephemera$decorate(ephemera$underline, ephemera$lastUnderline, glyph,
			minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset, original);
		ephemera$lastUnderline = effect;
		return effect;
	}

	@Unique
	private TextRenderable ephemera$decorate(
		TextDecorationRunMerger merger, TextRenderable previous, EffectGlyph glyph,
		float minX, float minY, float maxX, float maxY, float depth,
		int color, int shadowColor, float shadowOffset, Operation<TextRenderable> original
	) {
		if (!GuiFeatures.get().mergeTextDecorations) {
			return original.call(glyph, minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset);
		}
		if (merger.extendOrStart(minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset)) {
			int index = ephemera$indexOfIdentity(previous);
			if (index >= 0) {
				effects.remove(index);
				return original.call(glyph, merger.minX(), merger.minY(), merger.maxX(), merger.maxY(),
					merger.depth(), merger.color(), merger.shadowColor(), merger.shadowOffset());
			}
			// previous quad is gone (effects discarded): restart the run from this quad alone
			merger.reset();
			merger.extendOrStart(minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset);
		}
		return original.call(glyph, minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset);
	}

	@Unique
	private int ephemera$indexOfIdentity(TextRenderable target) {
		List<TextRenderable> list = effects;
		if (list == null || target == null) {
			return -1;
		}
		for (int i = list.size() - 1; i >= 0; i--) {
			if (list.get(i) == target) {
				return i;
			}
		}
		return -1;
	}
}
