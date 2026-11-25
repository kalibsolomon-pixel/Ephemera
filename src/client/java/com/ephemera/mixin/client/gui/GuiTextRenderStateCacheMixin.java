package com.ephemera.mixin.client.gui;

import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.gui.OriginNormalizedText;
import com.ephemera.render.gui.WorldPreparedTextCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caches immutable {@link Font.PreparedText} instances for HUD/GUI text lines (scoreboard sidebar,
 * bossbars, actionbar, chat lines, tab list, and overlays) across frames. When {@code GuiTextBatcher}
 * is active, prepares and caches text at origin {@code (0, 0)} and shifts bounds/rendering by
 * {@code (x, y)} so scrolling chat messages stay 100% cache-resident as lines shift upward.
 */
@Mixin(GuiTextRenderState.class)
abstract class GuiTextRenderStateCacheMixin implements OriginNormalizedText {
	@Unique private static final Matrix3x2f EPHEMERA$SCRATCH_POSE = new Matrix3x2f();
	@Unique private static int ephemera$batcherReady = -1;

	@Shadow @Final private int x;
	@Shadow @Final private int y;

	@Unique private boolean ephemera$originNormalized;

	@Unique
	private static boolean ephemera$isTextBatcherApplied() {
		int state = ephemera$batcherReady;
		if (state < 0) {
			state = MixinHealth.applied().contains("GuiRendererTextBatchMixin") ? 1 : 0;
			ephemera$batcherReady = state;
		}
		return state == 1;
	}

	@Override
	public boolean ephemera$isOriginNormalized() {
		return ephemera$originNormalized;
	}

	@Override
	public int ephemera$offsetX() {
		return this.x;
	}

	@Override
	public int ephemera$offsetY() {
		return this.y;
	}

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
		boolean normalize = (x != 0.0f || y != 0.0f)
			&& GuiFeatures.get().fastLtrText
			&& RenderSystem.isOnRenderThread()
			&& ephemera$isTextBatcherApplied();
		float px = normalize ? 0.0f : x;
		float py = normalize ? 0.0f : y;
		this.ephemera$originNormalized = normalize;
		Font.PreparedText cached = WorldPreparedTextCache.lookup(
			font, seq, px, py, color, dropShadow, includeEmpty, backgroundColor);
		if (cached != null) {
			return cached;
		}
		Font.PreparedText built = original.call(font, seq, px, py, color, dropShadow, includeEmpty, backgroundColor);
		WorldPreparedTextCache.store(font, seq, px, py, color, dropShadow, includeEmpty, backgroundColor, built);
		return built;
	}

	@WrapOperation(
		method = "ensurePrepared",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/navigation/ScreenRectangle;transformMaxBounds(Lorg/joml/Matrix3x2fc;)Lnet/minecraft/client/gui/navigation/ScreenRectangle;"
		)
	)
	private ScreenRectangle ephemera$translateOriginBounds(
		ScreenRectangle rect,
		Matrix3x2fc pose,
		Operation<ScreenRectangle> original
	) {
		if (this.ephemera$originNormalized) {
			return original.call(rect, EPHEMERA$SCRATCH_POSE.set(pose).translate(this.x, this.y));
		}
		return original.call(rect, pose);
	}
}
