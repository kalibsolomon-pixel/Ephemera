package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.locks.LockSupport;

/**
 * Vanilla's {@code CompileTask.doTask} retries a failed staging append in a tight
 * {@code Thread.onSpinWait()} loop while holding its {@code SectionBufferBuilderPack}, starving
 * the builder pool and burning a worker core (competing with the integrated server / worldgen)
 * during chunk-flight and rebuild bursts. Parking 100 us keeps the retry cadence but yields the
 * core so other workers and the render thread proceed.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskSpinParkMixin {
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
