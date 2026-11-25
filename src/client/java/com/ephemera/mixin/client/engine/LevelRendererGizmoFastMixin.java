package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.engine.FastGizmoCollector;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips allocating two {@code DrawableGizmoPrimitives} (10 {@code ArrayList}s) and a new
 * {@code FinalizedGizmos} every frame when no gizmos are active and {@code finalizedGizmos} is
 * already empty (~107 MB saved on the render thread per 45s benchmark).
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererGizmoFastMixin {
    @Shadow @Final private SimpleGizmoCollector renderThreadGizmos;
    @Unique private boolean ephemera$lastGizmosEmpty = true;

    @Inject(method = "finalizeGizmoCollection", at = @At("HEAD"), cancellable = true)
    private void ephemera$skipEmptyFinalize(CallbackInfo ci) {
        if (!EphemeraCommon.config().engineIdleShortCircuits) {
            ephemera$lastGizmosEmpty = false;
            return;
        }
        boolean empty = this.renderThreadGizmos instanceof FastGizmoCollector fast && fast.ephemera$isEmpty();
        if (empty && ephemera$lastGizmosEmpty) {
            ci.cancel();
            return;
        }
        ephemera$lastGizmosEmpty = empty;
    }
}
