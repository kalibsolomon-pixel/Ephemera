package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlStateCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.VertexArrayCache.VertexArray;
import com.mojang.blaze3d.vertex.VertexFormat;
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

	@Unique
	private static boolean ephemera$sameFormats(VertexFormat[] snapshot, Object[] elements) {
		if (snapshot == null || snapshot.length != elements.length) {
			return false;
		}
		for (int i = 0; i < snapshot.length; i++) {
			if (snapshot[i] != elements[i]) {
				return false;
			}
		}
		return true;
	}

	@WrapOperation(method = "bindVertexArray", at = @At(value = "INVOKE",
		target = "Ljava/util/Arrays;asList([Ljava/lang/Object;)Ljava/util/List;"))
	private List<?> ephemera$useIdentityKey(Object[] elements, Operation<List<?>> original,
			@Share("slot") LocalIntRef cacheSlot, @Share("hit") LocalBooleanRef cacheHit) {
		if (!DispatchSettings.glDispatch()) {
			cacheSlot.set(-1);
			cacheHit.set(false);
			return original.call((Object) elements);
		}
		int slot = System.identityHashCode(elements) & (EPHEMERA_CACHE_SIZE - 1);
		cacheSlot.set(slot);
		if (ephemera$keys[slot] == elements && ephemera$sameFormats(ephemera$snapshots[slot], elements)) {
			cacheHit.set(true);
			return null; // The paired Map.get wrapper returns the cached VAO.
		}
		cacheHit.set(false);
		return original.call((Object) elements);
	}

	@WrapOperation(method = "bindVertexArray", at = @At(value = "INVOKE",
		target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object ephemera$lookupVertexArray(Map<?, ?> cache, Object key, Operation<Object> original,
			@Share("slot") LocalIntRef cacheSlot) {
		return key == null && cacheSlot.get() >= 0 ? ephemera$arrays[cacheSlot.get()] : original.call(cache, key);
	}

	// The hit path re-binds vao.id on every draw — even when that exact VAO is already bound
	// from the previous draw. GlStateCache tracks the true bound VAO (all binds route through
	// GlStateManager._glBindVertexArray), so skip the redundant driver call. The creation path's
	// freshly generated id never matches the tracked bound id, so it always binds.
	@WrapOperation(method = "bindVertexArray", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindVertexArray(I)V"))
	private void ephemera$skipRedundantVaoBind(int vaoId, Operation<Void> original) {
		if (DispatchSettings.glDispatch() && GlStateCache.isVertexArrayBound(vaoId)) {
			return;
		}
		original.call(vaoId);
	}

	@Inject(method = "bindVertexArray", at = @At("RETURN"))
	private void ephemera$rememberVertexArray(VertexFormat[] formats, GpuBufferSlice[] buffers, VertexArray previous,
			CallbackInfoReturnable<VertexArray> ci, @Share("slot") LocalIntRef cacheSlot, @Share("hit") LocalBooleanRef cacheHit) {
		if (cacheHit.get()) {
			return;
		}
		int slot = cacheSlot.get();
		if (slot >= 0) {
			ephemera$keys[slot] = formats;
			ephemera$snapshots[slot] = formats.clone();
			ephemera$arrays[slot] = ci.getReturnValue();
		}
	}
}
