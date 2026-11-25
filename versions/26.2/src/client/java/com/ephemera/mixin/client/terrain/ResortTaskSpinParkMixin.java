package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.locks.LockSupport;

/**
 * Same staging-full retry spin as {@code CompileTask.doTask}, present in the transparency-resort
 * task as well; park instead of spin for the same reasons (pack retention + core yield).
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask")
public abstract class ResortTaskSpinParkMixin {
	@WrapOperation(
		method = "doTask",
		at = @At(value = "INVOKE", target = "Ljava/lang/Thread;onSpinWait()V")
	)
	private static void ephemera$parkInsteadOfSpin(Operation<Void> original) {
		if (EphemeraCommon.config().framePacerAdaptiveUploadBudget) {
			LockSupport.parkNanos(100_000L);
			return;
		}
		original.call();
	}
}
