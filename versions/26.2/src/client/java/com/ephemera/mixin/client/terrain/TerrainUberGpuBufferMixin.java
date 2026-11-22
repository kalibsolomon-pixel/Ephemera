package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.UberGpuBufferSlot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * Mirrors {@code allocationMap} writes onto the key mesh's {@link SectionMeshRenderCache}
 * slot. Vanilla's map stays authoritative and untouched; the mirror is only used by the
 * chunk batcher, which reads it under the same dispatcher lock the writes happen under.
 */
@Mixin(UberGpuBuffer.class)
public abstract class TerrainUberGpuBufferMixin implements UberGpuBufferSlot {
	@Unique
	private int ephemera$slot = -1;
	@Unique
	private int ephemera$epoch;

	@Override
	public int ephemera$slot() {
		return ephemera$slot;
	}

	@Override
	public int ephemera$epoch() {
		return ephemera$epoch;
	}

	@Override
	public void ephemera$assignSlot(int slot, int epoch) {
		ephemera$slot = slot;
		ephemera$epoch = epoch;
	}

	@WrapOperation(
		method = "uploadStagedAllocations",
		at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
	)
	private Object ephemera$mirrorPut(Map<Object, Object> map, Object key, Object value, Operation<Object> original) {
		Object previous = original.call(map, key, value);
		if (ephemera$slot >= 0 && key instanceof SectionMeshRenderCache cache && value instanceof TlsfAllocator.Allocation allocation) {
			cache.ephemera$setAllocation(ephemera$slot, ephemera$epoch, allocation);
		}
		return previous;
	}

	@WrapOperation(
		method = "freeAllocation",
		at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;")
	)
	private Object ephemera$mirrorRemove(Map<Object, Object> map, Object key, Operation<Object> original) {
		Object removed = original.call(map, key);
		if (ephemera$slot >= 0 && key instanceof SectionMeshRenderCache cache) {
			cache.ephemera$setAllocation(ephemera$slot, ephemera$epoch, null);
		}
		return removed;
	}
}
