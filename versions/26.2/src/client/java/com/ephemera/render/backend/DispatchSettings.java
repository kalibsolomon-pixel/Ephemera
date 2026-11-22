package com.ephemera.render.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.api.EphemeraApi;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

public final class DispatchSettings {
	private DispatchSettings() {}

	public interface GlUniformState {
		boolean ephemera$hasDirtyUniforms();
	}

	public static boolean glDispatch() {
		return EphemeraCommon.config().backendGlDispatchCache && !externalShaderPack();
	}

	public static boolean externalShaderPack() {
		return EphemeraApi.get().map(service -> service.shaders().isExternalShaderPackActive()).orElse(false);
	}

	public static boolean sameSlice(GpuBufferSlice previous, GpuBufferSlice next) {
		return previous != null && next != null
			&& sameSlice(previous, next.buffer(), next.offset(), next.length());
	}

	public static boolean sameSlice(GpuBufferSlice previous, GpuBuffer buffer, long offset, long length) {
		return previous != null && previous.buffer() == buffer
			&& previous.offset() == offset && previous.length() == length;
	}
}
