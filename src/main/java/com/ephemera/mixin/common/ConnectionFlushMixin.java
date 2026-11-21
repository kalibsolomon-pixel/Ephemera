package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.flush.FlushConsolidationHandler;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionFlushMixin {
    @Inject(method = "configurePacketHandler", at = @At("HEAD"))
    private void ephemera$batchFlushes(ChannelPipeline pipeline, CallbackInfo ci) {
        if (EphemeraCommon.config().networkFlushConsolidation && pipeline.get(FlushConsolidationHandler.class) == null) {
            pipeline.addFirst("ephemera_flush", new FlushConsolidationHandler(256, true));
        }
    }
}
