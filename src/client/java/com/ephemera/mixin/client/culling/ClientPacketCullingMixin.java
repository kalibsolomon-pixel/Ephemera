package com.ephemera.mixin.client.culling;

import com.ephemera.render.culling.EntityOcclusionManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketCullingMixin {
    @Inject(method = "handleEntityLinkPacket", at = @At("RETURN"))
    private void ephemera$leash(ClientboundSetEntityLinkPacket packet, CallbackInfo ci) { EntityOcclusionManager.leashPacket(packet.getDestId()); }
    @Inject(method = {"handleChunkBlocksUpdate", "updateLevelChunk", "handleForgetLevelChunk"}, at = @At("RETURN"))
    private void ephemera$terrain(CallbackInfo ci) { EntityOcclusionManager.terrainChanged(); }
}
