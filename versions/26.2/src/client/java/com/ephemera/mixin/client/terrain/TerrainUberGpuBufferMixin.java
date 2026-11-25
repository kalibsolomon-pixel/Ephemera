package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.UberGpuBufferBudgetControl;
import com.ephemera.render.terrain.UberGpuBufferSlot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * Mirrors {@code allocationMap} writes onto the key mesh's {@link SectionMeshRenderCache}
 * slot and exposes atomic overflow deferral so {@code uploadTerrainBuffersToGpu()} can pace
 * large chunk-rebuild / chunk-flight GPU upload bursts across frames on both OpenGL and Vulkan.
 */
@Mixin(UberGpuBuffer.class)
public abstract class TerrainUberGpuBufferMixin implements UberGpuBufferSlot, UberGpuBufferBudgetControl {
	@Shadow @Final private Object2ObjectOpenHashMap<Object, Object> stagedAllocations;
	@Shadow @Final private ObjectOpenHashSet<Object> skippedStagedAllocations;

	@Unique private int ephemera$slot = -1;
	@Unique private int ephemera$epoch;
	@Unique private Object2ObjectOpenHashMap<Object, Object> ephemera$deferred;

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

	@Override
	public int ephemera$stagedCount() {
		return this.stagedAllocations.size();
	}

	@Override
	public void ephemera$pickOverflowKeys(int maxAdmit, ObjectOpenHashSet<Object> outDeferred) {
		int admitted = 0;
		for (Object key : this.stagedAllocations.keySet()) {
			if (this.skippedStagedAllocations.contains(key)) {
				continue;
			}
			if (admitted < maxAdmit) {
				admitted++;
			} else {
				outDeferred.add(key);
			}
		}
	}

	@Override
	public void ephemera$deferKeys(ObjectOpenHashSet<Object> deferredKeys) {
		if (deferredKeys.isEmpty() || this.stagedAllocations.isEmpty()) {
			return;
		}
		Object2ObjectOpenHashMap<Object, Object> def = this.ephemera$deferred;
		for (Object key : deferredKeys) {
			if (this.skippedStagedAllocations.contains(key)) {
				continue;
			}
			Object entry = this.stagedAllocations.remove(key);
			if (entry != null) {
				if (def == null) {
					def = new Object2ObjectOpenHashMap<>(Math.max(16, deferredKeys.size()));
					this.ephemera$deferred = def;
				}
				def.put(key, entry);
			}
		}
	}

	@Override
	public void ephemera$restoreDeferred() {
		Object2ObjectOpenHashMap<Object, Object> def = this.ephemera$deferred;
		if (def != null && !def.isEmpty()) {
			this.stagedAllocations.putAll(def);
			def.clear();
		}
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
