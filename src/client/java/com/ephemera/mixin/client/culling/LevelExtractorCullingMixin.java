package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.EntityExtractPassCache;
import com.ephemera.render.culling.EntityOcclusionManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorCullingMixin {
    @Shadow @Final private LevelRenderer levelRenderer;

    @Inject(method = "extract", at = @At("HEAD"))
    private void ephemera$begin(DeltaTracker tracker, Camera camera, float partialTick, CallbackInfo ci) { EntityOcclusionManager.beginFrame(camera); }
    @Inject(method = "extract", at = @At("RETURN"))
    private void ephemera$end(CallbackInfo ci) {
        if (this.levelRenderer.entityRenderDispatcher() instanceof EntityExtractPassCache passCache) {
            passCache.ephemera$endExtractPass();
        }
        int sections = EphemeraCommon.config().diagnosticsHudOverlay
            ? ((LevelExtractor) (Object) this).countRenderedSections() : 0;
        EntityOcclusionManager.endFrame(sections);
    }
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void ephemera$beginEntityExtractPass(Camera camera, Frustum frustum, DeltaTracker tracker,
                                                 LevelRenderState state, CallbackInfo ci) {
        if (EphemeraCommon.config().entityRendererDirectCache
            && this.levelRenderer.entityRenderDispatcher() instanceof EntityExtractPassCache passCache) {
            passCache.ephemera$beginExtractPass();
        }
    }
    @Inject(method = "extractVisibleEntities", at = @At("RETURN"))
    private void ephemera$endEntityExtractPass(CallbackInfo ci) {
        if (this.levelRenderer.entityRenderDispatcher() instanceof EntityExtractPassCache passCache) {
            passCache.ephemera$endExtractPass();
        }
    }
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ephemera$world(ClientLevel level, CallbackInfo ci) {
        if (this.levelRenderer.entityRenderDispatcher() instanceof EntityExtractPassCache passCache) {
            passCache.ephemera$endExtractPass();
        }
        EntityOcclusionManager.reset(level);
    }
}
