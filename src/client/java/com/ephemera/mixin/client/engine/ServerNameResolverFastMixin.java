package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.network.FastIpAddress;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips blocking DNS SRV ({@code _minecraft._tcp}) redirect queries for literal numeric IPv4/IPv6
 * server addresses (RFC 2782 forbids SRV targets on numeric IPs), eliminating multi-second ping
 * and connection delays on LAN and direct-IP servers.
 */
@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverFastMixin {
	@WrapOperation(
		method = "resolveAddress",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;lookupRedirect(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"
		)
	)
	private Optional<ServerAddress> ephemera$skipSrvOnNumericIp(
		ServerRedirectHandler handler,
		ServerAddress address,
		Operation<Optional<ServerAddress>> original
	) {
		if (EphemeraCommon.config().loadingFastIpPing && address != null && FastIpAddress.isNumericIp(address.getHost())) {
			return Optional.empty();
		}
		return original.call(handler, address);
	}
}
