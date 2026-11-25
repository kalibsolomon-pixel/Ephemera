package com.ephemera.mixin.client.terrain;

import com.ephemera.render.pacing.StagingFlushState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Flags the render-thread emergency upload: when a section append fails because the staging
 * arena is full, vanilla's render-thread retry loop calls {@code uploadTerrainBuffersToGpu()}
 * inline. Marking that call lets the paced upload bypass per-layer caps for one pass so the
 * arena drains fully and the retrying task immediately makes progress instead of parking.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionEmergencyFlushMixin {
	@WrapOperation(
		method = "addSectionBuffersToUberBuffer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"
		)
	)
	private void ephemera$markEmergencyFlush(SectionRenderDispatcher dispatcher, Operation<Void> original) {
		StagingFlushState.set(true);
		try {
			original.call(dispatcher);
		} finally {
			StagingFlushState.set(false);
		}
	}
}
