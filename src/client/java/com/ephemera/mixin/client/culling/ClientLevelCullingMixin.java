package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.EntityOcclusionManager;
import com.ephemera.render.culling.ParticleCullingManager;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelCullingMixin {
    @Inject(method = {"setBlocksDirty", "sendBlockUpdated", "onChunkLoaded", "unload", "setServerVerifiedBlockState",
            "setSectionDirtyWithNeighbors", "setSectionRangeDirty"}, at = @At("HEAD"))
    private void ephemera$terrain(CallbackInfo ci) { EntityOcclusionManager.terrainChanged(); }
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void ephemera$mutation(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) EntityOcclusionManager.terrainChanged();
    }
    @Inject(method = "pollLightUpdates", at = @At("RETURN"))
    private void ephemera$lighting(CallbackInfo ci) { ParticleCullingManager.invalidateLight(); }
    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void ephemera$leashes(CallbackInfo ci) { EntityOcclusionManager.trackLeashes((ClientLevel) (Object) this); }
}
