package com.ephemera.mixin.client.visuals;

import com.ephemera.core.visuals.ChunkFadeMath;
import com.ephemera.visuals.VisualsRuntime;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Eases newly uploaded sections in with a cubic ease-out instead of vanilla's linear ramp. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionFadeMixin {
	@Inject(method = "getVisibility", at = @At("RETURN"), cancellable = true)
	private void ephemera$easeOut(long nowMs, CallbackInfoReturnable<Float> cir) {
		float linear = cir.getReturnValueF();
		if (linear > 0.0f && linear < 1.0f && VisualsRuntime.smoothChunkFade()) {
			cir.setReturnValue(ChunkFadeMath.easeOutCubic(linear));
		}
	}

	/** Vanilla's fade is off at 0; otherwise the configured duration replaces its slider value. */
	@ModifyVariable(method = "setFadeDuration", at = @At("HEAD"), argsOnly = true)
	private long ephemera$fadeDuration(long vanillaMs) {
		if (vanillaMs <= 0L || !VisualsRuntime.smoothChunkFade()) {
			return vanillaMs;
		}
		long configured = ChunkFadeMath.fadeDurationMs(VisualsRuntime.cfg().visualChunkFadeDurationSeconds);
		return configured > 0L ? configured : vanillaMs;
	}
}
