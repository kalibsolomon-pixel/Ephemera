package com.ephemera.mixin.client.backend;

import com.mojang.blaze3d.vertex.StagingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the chunk staging arena's fill state for paced-upload watermark checks. */
@Mixin(StagingBuffer.class)
public interface StagingBufferUsageAccessor {
	@Accessor("nextWriteOffset")
	int ephemera$nextWriteOffset();

	@Accessor("usedBufferCount")
	int ephemera$usedBufferCount();
}
