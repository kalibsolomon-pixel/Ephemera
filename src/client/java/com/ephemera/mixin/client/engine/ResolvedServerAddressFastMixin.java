package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eliminates blocking reverse-DNS (PTR) lookups when resolving or pinging numeric IPv4/IPv6
 * servers (even when Mojang's server blocklist is populated). Uses {@link InetSocketAddress#getHostString()}
 * instead of {@link InetAddress#getHostName()}, returning the domain name when resolved by name
 * and the numeric IP string immediately when connected by raw IP.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.resolver.ResolvedServerAddress$1")
abstract class ResolvedServerAddressFastMixin {
	@Shadow @Final private InetSocketAddress val$address;

	@WrapOperation(
		method = "getHostName()Ljava/lang/String;",
		at = @At(value = "INVOKE", target = "Ljava/net/InetAddress;getHostName()Ljava/lang/String;")
	)
	private String ephemera$skipReverseDns(InetAddress instance, Operation<String> original) {
		if (EphemeraCommon.config().loadingFastIpPing && val$address != null) {
			return val$address.getHostString();
		}
		return original.call(instance);
	}
}
