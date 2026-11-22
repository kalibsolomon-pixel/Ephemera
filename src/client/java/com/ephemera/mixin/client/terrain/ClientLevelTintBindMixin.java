package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.BlockTintCachePrefixHook;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags vanilla {@link BlockTintCache} instances created by {@link ClientLevel} with their
 * owning level and {@link ColorResolver} so prefix-sum biome blending can populate full
 * 16x16 chunk layers on miss while leaving custom mod tint caches untouched.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelTintBindMixin {
	@Shadow @Final private Object2ObjectArrayMap<ColorResolver, BlockTintCache> tintCaches;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$bindTintCaches(CallbackInfo ci) {
		ClientLevel self = (ClientLevel) (Object) this;
		for (var entry : this.tintCaches.object2ObjectEntrySet()) {
			if (entry.getValue() instanceof BlockTintCachePrefixHook hook) {
				hook.ephemera$bind(self, entry.getKey());
			}
		}
	}
}
