package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.render.BufferViewCache;
import com.ephemera.render.backend.VulkanBufferViews;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.vulkan.VK12.vkDestroyBufferView;

@Mixin(VulkanGpuBuffer.Direct.class)
abstract class VulkanDirectBufferMixin implements VulkanBufferViews {
	@Shadow @Final protected VulkanDevice device;
	@Shadow private int mappingRefCount;
	@Shadow public abstract boolean isClosed();

	@Unique private BufferViewCache ephemera$views;
	@Unique private long ephemera$mappedPtr;

	// The allocation runs before super(), so the constructor-local pointer is stored at return.
	@WrapOperation(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;Ljava/util/function/Supplier;IJZ)V",
		at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/util/vma/Vma;vmaCreateBuffer(JLorg/lwjgl/vulkan/VkBufferCreateInfo;"
			+ "Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;"
			+ "Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"))
	private static int ephemera$createMappedBuffer(long allocator, VkBufferCreateInfo bufferInfo,
			VmaAllocationCreateInfo allocInfo, LongBuffer buffer, PointerBuffer allocation, VmaAllocationInfo outInfo,
			Operation<Integer> original, @Share("mappedPtr") LocalLongRef mappedPtr) {
		int flags = allocInfo.flags();
		// VMA's automatic memory selection requires a host access flag for mapped allocations.
		int hostAccess = Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
			| Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
		if (outInfo != null || (flags & hostAccess) == 0
				|| (allocInfo.requiredFlags() & VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0
				|| !EphemeraCommon.config().backendVulkanModernGpu) {
			return original.call(allocator, bufferInfo, allocInfo, buffer, allocation, outInfo);
		}
		allocInfo.flags(flags | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
			int result = original.call(allocator, bufferInfo, allocInfo, buffer, allocation, info);
			if (result == VK12.VK_SUCCESS) {
				mappedPtr.set(info.pMappedData());
			}
			return result;
		}
	}

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;Ljava/util/function/Supplier;IJZ)V",
		at = @At("RETURN"))
	private void ephemera$storeMappedPointer(CallbackInfo ci, @Share("mappedPtr") LocalLongRef mappedPtr) {
		ephemera$mappedPtr = mappedPtr.get();
	}

	@Inject(method = "map(JJZZ)Lcom/mojang/blaze3d/buffers/GpuBufferSlice$MappedView;",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$mapPersistent(long offset, long length, boolean read, boolean write,
			CallbackInfoReturnable<GpuBufferSlice.MappedView> cir) {
		if (ephemera$mappedPtr == 0L) {
			return;
		}
		if (isClosed()) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (!read && !write) {
			throw new IllegalArgumentException("At least read or write must be true");
		}
		VulkanGpuBuffer self = (VulkanGpuBuffer) (Object) this;
		int usage = self.usage();
		if (read && (usage & GpuBuffer.USAGE_MAP_READ) == 0) {
			throw new IllegalStateException("Buffer is not readable");
		}
		if (write && (usage & GpuBuffer.USAGE_MAP_WRITE) == 0) {
			throw new IllegalStateException("Buffer is not writable");
		}
		long size = self.size();
		if (offset + length > size) {
			throw new IllegalArgumentException("Cannot map more data than this buffer can hold (attempting to map "
				+ length + " bytes at offset " + offset + " from " + size + " size buffer)");
		}
		if (length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
		}
		if (offset < 0 || length < 0) {
			throw new IllegalArgumentException("Offset or length must be positive integer values");
		}

		mappingRefCount++;
		ByteBuffer data = MemoryUtil.memByteBuffer(ephemera$mappedPtr + offset, (int) length);
		cir.setReturnValue(new GpuBufferSlice.MappedView(self.slice(offset, length), data, new PersistentUnmap(this)));
	}

	@Unique
	void ephemera$decMapRef() {
		mappingRefCount--;
	}

	private static final class PersistentUnmap implements Runnable {
		private final VulkanDirectBufferMixin owner;
		private boolean closed;

		PersistentUnmap(VulkanDirectBufferMixin owner) {
			this.owner = owner;
		}

		@Override
		public void run() {
			if (!closed) {
				closed = true;
				owner.ephemera$decMapRef();
			}
		}
	}

	@Override
	public BufferViewCache ephemera$bufferViews() {
		if (ephemera$views == null) {
			ephemera$views = new BufferViewCache(16);
		}
		return ephemera$views;
	}

	@Inject(method = "destroy", at = @At("HEAD"))
	private void ephemera$destroyViews(CallbackInfo ci) {
		ephemera$mappedPtr = 0L;
		// close() queues this destroy() after submitted users retire. Views must die before the VkBuffer.
		if (ephemera$views != null) {
			VkDevice nativeDevice = device.vkDevice();
			ephemera$views.destroyAll(view -> vkDestroyBufferView(nativeDevice, view, null));
			ephemera$views = null;
		}
	}
}
