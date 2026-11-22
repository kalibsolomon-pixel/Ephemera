package com.ephemera.mixin.client.gui;

import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.gui.GuiTextBatcher;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's per-glyph {@code GlyphRenderState} + {@code GuiRenderer$1} visitor emission in
 * {@code prepareText} with {@link GuiTextBatcher}, which submits one pooled element per contiguous
 * {@code (RenderPipeline, GpuTextureView)} run of each text state. Pooled runs are released only
 * after the frame's {@code GuiRenderState.reset()} so drawn elements never observe recycled glyphs.
 */
@Mixin(GuiRenderer.class)
abstract class GuiRendererTextBatchMixin {
	@Shadow @Final private GuiRenderState renderState;

	@Unique private final GuiTextBatcher ephemera$textBatcher = new GuiTextBatcher();

	@Inject(method = "prepareText", at = @At("HEAD"))
	private void ephemera$resetBeforePrepare(CallbackInfo ci) {
		ephemera$textBatcher.reset();
	}

	@Inject(
		method = "lambda$prepareText$0(Lnet/minecraft/client/renderer/state/gui/GuiTextRenderState;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ephemera$batchTextRuns(GuiTextRenderState state, CallbackInfo ci) {
		if (GuiFeatures.get().fastLtrText && RenderSystem.isOnRenderThread()) {
			ephemera$textBatcher.emit(this.renderState, state);
			ci.cancel();
		}
	}

	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;reset()V")
	)
	private void ephemera$resetAfterDraw(CallbackInfo ci) {
		ephemera$textBatcher.reset();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void ephemera$resetOnClose(CallbackInfo ci) {
		ephemera$textBatcher.reset();
	}
}
