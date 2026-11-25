package com.ephemera.mixin.common;

import com.ephemera.engine.common.CowLongIntOpenHashMap;
import com.ephemera.engine.common.LightMapStorageAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates both {@code Long2ObjectOpenHashMap.clone()} and redundant {@code topSections.clone()}
 * inside {@code SkyDataLayerStorageMap.copy()} using O(1) sharded / dirty-tracked copy-on-write
 * snapshots.
 */
@Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
abstract class SkyLightMapCopyMixin {
	@Shadow @Final @Mutable private Long2IntOpenHashMap topSections;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$wrapTopSectionsCow(
		Long2ObjectOpenHashMap<DataLayer> map,
		Long2IntOpenHashMap topSections,
		int lowestY,
		CallbackInfo ci
	) {
		if (!(this.topSections instanceof CowLongIntOpenHashMap)) {
			this.topSections = new CowLongIntOpenHashMap(this.topSections);
		}
	}

	@WrapOperation(
		method = "copy()Lnet/minecraft/world/level/lighting/SkyLightSectionStorage$SkyDataLayerStorageMap;",
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
		method = "copy()Lnet/minecraft/world/level/lighting/SkyLightSectionStorage$SkyDataLayerStorageMap;",
		at = @At("RETURN")
	)
	private void ephemera$attachShardedSnapshot(CallbackInfoReturnable<?> cir) {
		Object copy = cir.getReturnValue();
		if (copy instanceof LightMapStorageAccess dst && (Object) this instanceof LightMapStorageAccess src) {
			dst.ephemera$setShardedMap(src.ephemera$snapshotMap());
		}
	}
}
