package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.flush.FlushConsolidationHandler;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Consolidates outbound Netty flushes triggered during inbound packet-read bursts while keeping
 * explicit game/server-thread flushes immediate ({@code consolidateWhenNoReadInProgress = false})
 * and draining pending writes before compression, encryption, or protocol-state transitions.
 */
@Mixin(Connection.class)
abstract class ConnectionFlushMixin {
	@Shadow private Channel channel;

	@Inject(method = "configurePacketHandler", at = @At("HEAD"))
	private void ephemera$batchFlushes(ChannelPipeline pipeline, CallbackInfo ci) {
		if (EphemeraCommon.config().networkFlushConsolidation && pipeline.get(FlushConsolidationHandler.class) == null) {
			pipeline.addFirst("ephemera_flush", new FlushConsolidationHandler(256, false));
		}
	}

	@Inject(method = "setupCompression", at = @At("HEAD"))
	private void ephemera$flushBeforeCompression(CallbackInfo ci) {
		ephemera$flushActiveChannel();
	}

	@Inject(method = "setEncryptionKey", at = @At("HEAD"))
	private void ephemera$flushBeforeEncryption(CallbackInfo ci) {
		ephemera$flushActiveChannel();
	}

	@Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
	private void ephemera$flushBeforeProtocolSwitch(CallbackInfo ci) {
		ephemera$flushActiveChannel();
	}

	@Unique
	private void ephemera$flushActiveChannel() {
		Channel ch = this.channel;
		if (ch != null && ch.isActive()) {
			ch.flush();
		}
	}
}
