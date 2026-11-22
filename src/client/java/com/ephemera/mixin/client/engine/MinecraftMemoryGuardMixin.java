package com.ephemera.mixin.client.engine;

import com.ephemera.engine.client.MemoryLeakGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sweeps all world-scoped Ephemera caches immediately when the client disconnects or switches
 * levels so old {@code ClientLevel}, {@code LocalPlayer}, and chunk-section graphs can be collected
 * on the very next GC cycle.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMemoryGuardMixin {
	@Inject(method = "clearClientLevel", at = @At("TAIL"))
	private void ephemera$sweepOnClearClientLevel(Screen screen, CallbackInfo ci) {
		MemoryLeakGuard.onWorldUnload();
	}
}
