package com.ephemera.render.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.render.BufferViewCache;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;

public interface VulkanBufferViews {
	BufferViewCache ephemera$bufferViews();

	static BufferViewCache cacheFor(GpuBufferSlice slice, long handle) {
		if (EphemeraCommon.config().backendVulkanBufferViewCache
				&& slice.buffer() instanceof VulkanGpuBuffer.Direct buffer
				&& buffer instanceof VulkanBufferViews owner && !buffer.isClosed() && handle == buffer.vkBuffer()) {
			return owner.ephemera$bufferViews();
		}
		return null;
	}
}
