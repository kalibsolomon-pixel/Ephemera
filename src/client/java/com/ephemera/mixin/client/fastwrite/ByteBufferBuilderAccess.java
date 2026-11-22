package com.ephemera.mixin.client.fastwrite;

import com.ephemera.render.fastwrite.FastBufferState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ByteBufferBuilder.class)
public abstract class ByteBufferBuilderAccess implements FastBufferState.Storage {
	@Shadow private long pointer;
	@Shadow private long capacity;
	@Shadow private long writeOffset;

	@Shadow protected abstract void checkOpen();
	@Shadow public abstract long reserve(int bytes);

	@WrapMethod(method = "reserve")
	private long ephemera$inlineReserveFastPath(int bytes, Operation<Long> original) {
		long ptr = this.pointer;
		long off = this.writeOffset;
		long next = off + bytes;
		if (ptr != 0L && bytes >= 0 && next <= this.capacity) {
			this.writeOffset = next;
			return ptr + off;
		}
		return original.call(bytes);
	}

	@Override
	public void ephemera$checkOpen() {
		this.checkOpen();
	}

	@Override
	public long ephemera$fastReserve(int bytes) {
		long ptr = this.pointer;
		long off = this.writeOffset;
		long next = off + bytes;
		if (ptr != 0L && bytes >= 0 && next <= this.capacity) {
			this.writeOffset = next;
			return ptr + off;
		}
		this.checkOpen();
		return this.reserve(bytes);
	}
}
