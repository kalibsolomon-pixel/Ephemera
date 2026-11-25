package com.ephemera.mixin.common;

import com.ephemera.engine.common.LightMapStorageAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates {@code Long2ObjectOpenHashMap.clone()} inside
 * {@code BlockDataLayerStorageMap.copy()} by publishing an O(1) sharded copy-on-write snapshot.
 */
@Mixin(targets = "net.minecraft.world.level.lighting.BlockLightSectionStorage$BlockDataLayerStorageMap")
abstract class BlockLightMapCopyMixin {
	@WrapOperation(
		method = "copy()Lnet/minecraft/world/level/lighting/BlockLightSectionStorage$BlockDataLayerStorageMap;",
		at = @At(
			value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;clone()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"
		)
	)
	private Long2ObjectOpenHashMap<DataLayer> ephemera$skipMonolithicClone(
		Long2ObjectOpenHashMap<DataLayer> map,
		Operation<Long2ObjectOpenHashMap<DataLayer>> original
	) {
		return LightMapStorageAccess.EMPTY_LIGHT_MAP;
	}

	@Inject(
		method = "copy()Lnet/minecraft/world/level/lighting/BlockLightSectionStorage$BlockDataLayerStorageMap;",
		at = @At("RETURN")
	)
	private void ephemera$attachShardedSnapshot(CallbackInfoReturnable<?> cir) {
		Object copy = cir.getReturnValue();
		if (copy instanceof LightMapStorageAccess dst && (Object) this instanceof LightMapStorageAccess src) {
			dst.ephemera$setShardedMap(src.ephemera$snapshotMap());
		}
	}
}
