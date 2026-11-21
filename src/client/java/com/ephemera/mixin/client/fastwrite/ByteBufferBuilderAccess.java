package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastBufferState;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ByteBufferBuilder.class)
public interface ByteBufferBuilderAccess extends FastBufferState.Storage {
	@Invoker("checkOpen") void ephemera$checkOpen();
}
