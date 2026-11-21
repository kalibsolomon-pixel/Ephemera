package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoid eager reverse DNS only when there are no consumers of the hostname. */
@Mixin(targets = "net.minecraft.client.multiplayer.resolver.AddressCheck$1")
abstract class NumericAddressMixin {
    @Shadow @Final private ImmutableList<?> val$blockLists;

    @Inject(method = "isAllowed(Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;)Z", at = @At("HEAD"), cancellable = true)
    private void ephemera$emptyFilters(ResolvedServerAddress address, CallbackInfoReturnable<Boolean> ci) {
        if (EphemeraCommon.config().loadingFastIpPing && val$blockLists.isEmpty()) ci.setReturnValue(true);
    }
}
