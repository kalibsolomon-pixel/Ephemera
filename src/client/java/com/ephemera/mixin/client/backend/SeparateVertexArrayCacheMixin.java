package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.VertexArrayCache.VertexArray;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.mojang.blaze3d.opengl.VertexArrayCache$Separate")
abstract class SeparateVertexArrayCacheMixin {
	@Unique private static final int EPHEMERA_CACHE_SIZE = 32;
	@Unique private final VertexFormat[][] ephemera$keys = new VertexFormat[EPHEMERA_CACHE_SIZE][];
	@Unique private final VertexFormat[][] ephemera$snapshots = new VertexFormat[EPHEMERA_CACHE_SIZE][];
	@Unique private final VertexArray[] ephemera$arrays = new VertexArray[EPHEMERA_CACHE_SIZE];

	@WrapOperation(method = "bindVertexArray", at = @At(value = "INVOKE",
		target = "Ljava/util/Arrays;asList([Ljava/lang/Object;)Ljava/util/List;"))
	private List<?> ephemera$useIdentityKey(Object[] elements, Operation<List<?>> original,
			@Share("slot") LocalIntRef cacheSlot) {
		if (!DispatchSettings.glDispatch()) {
			cacheSlot.set(-1);
			return original.call((Object) elements);
		}
		int slot = System.identityHashCode(elements) & (EPHEMERA_CACHE_SIZE - 1);
		cacheSlot.set(slot);
		if (ephemera$keys[slot] == elements && Arrays.equals(ephemera$snapshots[slot], elements)) {
			return null; // The paired Map.get wrapper returns the cached VAO.
		}
		return original.call((Object) elements);
	}

	@WrapOperation(method = "bindVertexArray", at = @At(value = "INVOKE",
		target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object ephemera$lookupVertexArray(Map<?, ?> cache, Object key, Operation<Object> original,
			@Share("slot") LocalIntRef cacheSlot) {
		return key == null && cacheSlot.get() >= 0 ? ephemera$arrays[cacheSlot.get()] : original.call(cache, key);
	}

	@Inject(method = "bindVertexArray", at = @At("RETURN"))
	private void ephemera$rememberVertexArray(VertexFormat[] formats, GpuBufferSlice[] buffers, VertexArray previous,
			CallbackInfoReturnable<VertexArray> ci, @Share("slot") LocalIntRef cacheSlot) {
		int slot = cacheSlot.get();
		if (slot >= 0 && (ephemera$keys[slot] != formats || !Arrays.equals(ephemera$snapshots[slot], formats))) {
			ephemera$keys[slot] = formats;
			ephemera$snapshots[slot] = formats.clone();
			ephemera$arrays[slot] = ci.getReturnValue();
		}
	}
}
