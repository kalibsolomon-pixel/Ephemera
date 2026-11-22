package com.ephemera.mixin.client.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the private heap buffer so an allocation resolves to its GPU buffer without the owning uber buffer. */
@Mixin(UberGpuBuffer.UberGpuBufferHeap.class)
public interface TerrainUberGpuBufferHeapAccessor {
	@Accessor("gpuBuffer")
	GpuBuffer getEphemeraGpuBuffer();
}
