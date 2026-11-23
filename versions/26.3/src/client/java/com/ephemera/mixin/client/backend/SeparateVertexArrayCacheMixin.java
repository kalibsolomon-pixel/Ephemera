package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.ARBVertexAttribBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 OpenGL {@code VertexArray$Separate} per-VAO vertex-buffer binding cache and active-slot
 * bitmask. Eliminates looping all 16 slots and skips redundant {@code glBindVertexBuffer} driver
 * calls when a VAO already has the exact same live buffer and offset bound.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.VertexArray$Separate")
abstract class SeparateVertexArrayCacheMixin {
	@Shadow @Final private boolean needsMesaWorkaround;
	@Shadow @Final private int[] strides;

	@Unique private int ephemera$activeMask;
	@Unique private final GlBuffer[] ephemera$boundBuffers = new GlBuffer[16];
	@Unique private final int[] ephemera$boundHandles = new int[16];
	@Unique private final long[] ephemera$boundOffsets = new long[16];

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$initActiveMask(CallbackInfo ci) {
		int mask = 0;
		int[] s = this.strides;
		for (int i = 0; i < s.length; i++) {
			if (s[i] != 0) {
				mask |= 1 << i;
			}
		}
		this.ephemera$activeMask = mask;
	}

	/**
	 * @author Ephemera
	 * @reason Active-slot bitmask iteration + per-VAO binding cache avoids redundant glBindVertexBuffer calls.
	 */
	@Overwrite
	public void bind(GpuBufferSlice[] slices) {
		int vaoId = ((VertexArrayIdAccess) this).ephemera$vertexArrayId();
		GlStateManager._glBindVertexArray(vaoId);
		int active = this.ephemera$activeMask;
		if (active == 0) {
			return;
		}
		boolean cache = DispatchSettings.glDispatch() && !this.needsMesaWorkaround;
		int[] s = this.strides;
		GlBuffer[] boundBufs = this.ephemera$boundBuffers;
		int[] boundHandles = this.ephemera$boundHandles;
		long[] boundOffsets = this.ephemera$boundOffsets;
		int len = slices.length;
		while (active != 0) {
			int i = Integer.numberOfTrailingZeros(active);
			active &= active - 1;
			if (i >= len) {
				break;
			}
			GpuBufferSlice slice = slices[i];
			if (slice == null) {
				throw new IllegalStateException("Vertex buffer slot " + i + " is not bound");
			}
			GpuBuffer raw = slice.buffer();
			GlBuffer glBuf = (GlBuffer) raw;
			int handle = glBuf.handle();
			long offset = slice.offset();
			if (cache && boundBufs[i] == glBuf && boundHandles[i] == handle && boundOffsets[i] == offset && !glBuf.isClosed()) {
				continue;
			}
			if (this.needsMesaWorkaround) {
				ARBVertexAttribBinding.glBindVertexBuffer(i, 0, 0L, 0);
			}
			ARBVertexAttribBinding.glBindVertexBuffer(i, handle, offset, s[i]);
			boundBufs[i] = glBuf;
			boundHandles[i] = handle;
			boundOffsets[i] = offset;
		}
	}
}
