package com.ephemera.mixin.client.engine;

import com.ephemera.engine.client.MemoryLeakGuard;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases transient model/state interning tables and stale caches immediately after a resource
 * pack reload applies so old models and atlases are never pinned in memory.
 */
@Mixin(ModelManager.class)
abstract class ModelManagerReloadMixin {
	@Inject(method = "apply", at = @At("RETURN"))
	private void ephemera$afterModelReload(CallbackInfo ci) {
		MemoryLeakGuard.onResourceReloadComplete();
	}
}
