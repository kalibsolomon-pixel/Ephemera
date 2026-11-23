package com.ephemera.mixin.client.gui;

import com.ephemera.core.gui.TextDecorationRunMerger;
import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.gui.PreparedTextCacheable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla emits one underline/strikethrough quad per glyph. Contiguous quads with identical attributes are
 * collapsed into one quad that replaces the previous one in the effect list. Also pre-sizes the glyph
 * list to 32 entries to avoid repeated {@code ArrayList.grow} reallocations on normal HUD/chat lines.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
abstract class FontPreparedTextBuilderMixin implements PreparedTextCacheable {
	private static final String ACCEPT = "accept(ILnet/minecraft/network/chat/Style;Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;)Z";
	private static final String CREATE_EFFECT = "Lnet/minecraft/client/gui/font/glyphs/EffectGlyph;createEffect(FFFFFIIF)Lnet/minecraft/client/gui/font/TextRenderable;";

	@Shadow private List<TextRenderable> effects;

	// created on the first decorated glyph: most text lines (HUD lines, tooltips) have no decoration at all
	@Unique private TextDecorationRunMerger ephemera$strikethrough;
	@Unique private TextDecorationRunMerger ephemera$underline;
	@Unique private TextRenderable ephemera$lastStrikethrough;
	@Unique private TextRenderable ephemera$lastUnderline;
	@Unique private boolean ephemera$hasObfuscated;

	@Inject(method = ACCEPT, at = @At("HEAD"))
	private void ephemera$trackObfuscated(int pos, Style style, BakedGlyph glyph, CallbackInfoReturnable<Boolean> cir) {
		if (style.isObfuscated()) {
			ephemera$hasObfuscated = true;
		}
	}

	@Override
	public boolean ephemera$isCacheable() {
		return !ephemera$hasObfuscated;
	}

	@WrapOperation(
		method = "<init>(Lnet/minecraft/client/gui/Font;FFIIZZ)V",
		at = @At(value = "NEW", target = "()Ljava/util/ArrayList;")
	)
	private ArrayList<?> ephemera$presizeGlyphList(Operation<ArrayList<?>> original) {
		return new ArrayList<>(32);
	}

	@WrapOperation(method = ACCEPT, at = @At(value = "INVOKE", target = CREATE_EFFECT, ordinal = 0))
	private TextRenderable ephemera$mergeStrikethrough(
		EffectGlyph glyph, float minX, float minY, float maxX, float maxY, float depth,
		int color, int shadowColor, float shadowOffset, Operation<TextRenderable> original
	) {
		if (!GuiFeatures.get().mergeTextDecorations) {
			return original.call(glyph, minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset);
		}
		TextDecorationRunMerger merger = ephemera$strikethrough;
		if (merger == null) {
			merger = ephemera$strikethrough = new TextDecorationRunMerger();
		}
		TextRenderable effect = ephemera$decorate(merger, ephemera$lastStrikethrough, glyph,
			minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset, original);
		ephemera$lastStrikethrough = effect;
		return effect;
	}

	@WrapOperation(method = ACCEPT, at = @At(value = "INVOKE", target = CREATE_EFFECT, ordinal = 1))
	private TextRenderable ephemera$mergeUnderline(
		EffectGlyph glyph, float minX, float minY, float maxX, float maxY, float depth,
		int color, int shadowColor, float shadowOffset, Operation<TextRenderable> original
	) {
		if (!GuiFeatures.get().mergeTextDecorations) {
			return original.call(glyph, minX, minY, maxX, maxY, depth, color, shadowColor, shadowOffset);
		}
		TextDecorationRunMerger merger = ephemera$underline;
		if (merger == null) {
			merger = ephemera$underline = new TextDecorationRunMerger();
		}
		TextRenderable effect = ephemera$decorate(merger, ephemera$lastUnderline, glyph,
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
