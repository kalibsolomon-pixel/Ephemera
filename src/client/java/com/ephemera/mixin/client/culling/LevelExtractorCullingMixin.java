package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.EntityOcclusionManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorCullingMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void ephemera$begin(DeltaTracker tracker, Camera camera, float partialTick, CallbackInfo ci) { EntityOcclusionManager.beginFrame(camera); }
    @Inject(method = "extract", at = @At("RETURN"))
    private void ephemera$end(CallbackInfo ci) { EntityOcclusionManager.endFrame(((LevelExtractor) (Object) this).countRenderedSections()); }
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ephemera$world(ClientLevel level, CallbackInfo ci) { EntityOcclusionManager.reset(level); }
}
