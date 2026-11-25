package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents synchronous render-thread chunk rebuild storms under vanilla's default
 * {@code PrioritizeChunkUpdates.NEARBY}. Non-player updates (flowing water/lava, redstone, crop
 * ticks, light propagation) offload to chunk builder workers instead of stalling the render thread,
 * while player block edits keep at most one synchronous compile within a tight 1.5 ms guard so
 * breaking/placing feels instant without multi-section corner spikes.
 */
@Mixin(LevelRenderer.class)
abstract class TerrainCompilePacingMixin {
    @Unique private static final long EPHEMERA_MAX_SYNC_BUDGET_NS = 1_500_000L;
    @Unique private int ephemera$syncCompilesThisFrame;
    @Unique private long ephemera$compileStartNs;

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void ephemera$resetSyncCompileBudget(CallbackInfo ci) {
        ephemera$syncCompilesThisFrame = 0;
        ephemera$compileStartNs = System.nanoTime();
    }

    @WrapOperation(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
        )
    )
    private void ephemera$paceSyncCompile(
        SectionRenderDispatcher.RenderSection section,
        RenderSectionRegion region,
        Operation<Void> original,
        @Local SectionUpdateRenderState updateState
    ) {
        if (!EphemeraCommon.config().framePacerAdaptiveUploadBudget) {
            original.call(section, region);
            return;
        }
        if (!updateState.playerChanged()) {
            section.compileAsync(region);
            return;
        }
        long now = System.nanoTime();
        FrameBudgetPacer budget = EphemeraPacerRuntime.budget();
        boolean hasHeadroom = budget.effectiveTargetFps() > 0
            ? budget.remainingStreamingHeadroomNs(now) >= 300_000L
            : (now - ephemera$compileStartNs) < EPHEMERA_MAX_SYNC_BUDGET_NS;
        if (ephemera$syncCompilesThisFrame == 0 && hasHeadroom) {
            ephemera$syncCompilesThisFrame = 1;
            original.call(section, region);
            budget.recordStreamingWork(System.nanoTime() - now);
        } else {
            section.compileAsync(region);
        }
    }
}
