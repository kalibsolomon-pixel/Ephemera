package com.ephemera.mixin.client.visuals;

import com.ephemera.core.visuals.ChunkFadeMath;
import com.ephemera.visuals.VisualsRuntime;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Eases newly uploaded sections in with a cubic ease-out instead of vanilla's linear ramp. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionFadeMixin {
	@ModifyReturnValue(method = "getVisibility", at = @At("RETURN"))
	private float ephemera$easeOut(float linear) {
		if (linear <= 0.0f || linear >= 1.0f || !VisualsRuntime.smoothChunkFade()) {
			return linear;
		}
		return ChunkFadeMath.easeOutCubic(linear);
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
