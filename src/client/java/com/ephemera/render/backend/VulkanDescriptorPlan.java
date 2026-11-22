package com.ephemera.render.backend;

import com.ephemera.core.render.BufferViewCache;
import com.ephemera.render.backend.RenderPassTextureBinding.Vulkan;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.lang.ref.Cleaner;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.KHRPushDescriptor.nvkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK12.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK12.nvkCreateBufferView;
import static org.lwjgl.vulkan.VK12.vkCreateBufferView;
import static org.lwjgl.vulkan.VK12.vkDestroyBufferView;

/**
 * Pre-compiled descriptor push template for a {@link VulkanBindGroupLayout}.
 *
 * <p>Pre-allocates and pre-links a single persistent off-heap native block containing the
 * {@code VkWriteDescriptorSet[]}, {@code VkDescriptorBufferInfo[]}, {@code VkDescriptorImageInfo[]},
 * and texel-view slots so Render-thread descriptor pushes do zero {@code MemoryStack} lookups,
 * zero {@code ThreadLocal} lookups, zero struct zeroing, and zero {@code HashMap.get} calls.
 */
public final class VulkanDescriptorPlan {
	private static final Cleaner CLEANER = Cleaner.create();
	private static final Map<VulkanBindGroupLayout, VulkanDescriptorPlan> PLANS = new WeakHashMap<>();
	private static final int RT_SET_BITS = 7; // 128 sets * 2 ways = 256 entries
	private static final int RT_CACHE_SIZE = (1 << RT_SET_BITS) * 2;
	private static final int RT_HASH_SHIFT = 64 - RT_SET_BITS;
	private static final VulkanBindGroupLayout[] RT_KEYS = new VulkanBindGroupLayout[RT_CACHE_SIZE];
	private static final VulkanDescriptorPlan[] RT_VALS = new VulkanDescriptorPlan[RT_CACHE_SIZE];

	private final String[] names;
	private final int[] slots;
	private final byte[] types;
	private final int[] texelFormats;
	private final int bindingCount;
	private final int uniformCount;
	private final int samplerCount;
	private final int texelCount;

	// Pre-linked off-heap template for single-threaded Render thread execution (zero MemoryStack/calloc).
	private final long nativeBlock;
	private final long writesAddr;
	private final long[] infoAddrs;
	private final long texelInfoAddr;

	public static VulkanDescriptorPlan of(VulkanBindGroupLayout layout) {
		if (RenderSystem.isOnRenderThread()) {
			long h = layout.handle();
			int base = ((int) ((h * 0x9E3779B97F4A7C15L) >>> RT_HASH_SHIFT)) << 1;
			if (RT_KEYS[base] == layout) {
				return RT_VALS[base];
			}
			int way1 = base + 1;
			if (RT_KEYS[way1] == layout) {
				return RT_VALS[way1];
			}
			VulkanDescriptorPlan plan = ofSlow(layout);
			RT_KEYS[way1] = RT_KEYS[base];
			RT_VALS[way1] = RT_VALS[base];
			RT_KEYS[base] = layout;
			RT_VALS[base] = plan;
			return plan;
		}
		return ofSlow(layout);
	}

	private static synchronized VulkanDescriptorPlan ofSlow(VulkanBindGroupLayout layout) {
		return PLANS.computeIfAbsent(layout, VulkanDescriptorPlan::new);
	}

	public static void clearRenderThreadCache() {
		RenderSystem.assertOnRenderThread();
		Arrays.fill(RT_KEYS, null);
		Arrays.fill(RT_VALS, null);
	}

	private VulkanDescriptorPlan(VulkanBindGroupLayout layout) {
		List<VulkanBindGroupLayout.Entry> entries = layout.entries();
		int count = entries.size();
		bindingCount = count;
		names = new String[count];
		slots = new int[count];
		types = new byte[count];
		texelFormats = new int[count];
		int uniforms = 0;
		int samplers = 0;
		int texels = 0;
		for (int i = 0; i < count; i++) {
			VulkanBindGroupLayout.Entry entry = entries.get(i);
			names[i] = entry.name();
			slots[i] = VulkanDescriptorBindings.slot(entry.name());
			switch (entry.type()) {
				case UNIFORM_BUFFER -> {
					types[i] = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
					uniforms++;
				}
				case SAMPLED_IMAGE -> {
					types[i] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
					samplers++;
				}
				case TEXEL_BUFFER -> {
					types[i] = VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
					texelFormats[i] = VulkanConst.toVk(entry.texelBufferFormat());
					texels++;
				}
			}
		}
		uniformCount = uniforms;
		samplerCount = samplers;
		texelCount = texels;

		int writesBytes = align16(count * VkWriteDescriptorSet.SIZEOF);
		int buffersBytes = align16(uniforms * VkDescriptorBufferInfo.SIZEOF);
		int imagesBytes = align16(samplers * VkDescriptorImageInfo.SIZEOF);
		int texelViewsBytes = align16(texels * Long.BYTES);
		int texelInfoBytes = texels == 0 ? 0 : align16(VkBufferViewCreateInfo.SIZEOF);
		int totalBytes = Math.max(16, writesBytes + buffersBytes + imagesBytes + texelViewsBytes + texelInfoBytes);

		long block = MemoryUtil.nmemCalloc(1L, totalBytes);
		nativeBlock = block;
		if (block != 0L) {
			CLEANER.register(this, new NativeFree(block));
		}
		writesAddr = block;
		long buffersBase = block + writesBytes;
		long imagesBase = buffersBase + buffersBytes;
		long texelsBase = imagesBase + imagesBytes;
		texelInfoAddr = texels == 0 ? 0L : texelsBase + texelViewsBytes;
		if (texelInfoAddr != 0L) {
			MemoryUtil.memPutInt(texelInfoAddr + VkBufferViewCreateInfo.STYPE, VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO);
		}

		infoAddrs = new long[count];
		int bi = 0, ii = 0, ti = 0;
		for (int i = 0; i < count; i++) {
			long w = writesAddr + (long) i * VkWriteDescriptorSet.SIZEOF;
			MemoryUtil.memPutInt(w + VkWriteDescriptorSet.STYPE, VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
			MemoryUtil.memPutInt(w + VkWriteDescriptorSet.DSTBINDING, i);
			MemoryUtil.memPutInt(w + VkWriteDescriptorSet.DESCRIPTORCOUNT, 1);
			int type = types[i];
			MemoryUtil.memPutInt(w + VkWriteDescriptorSet.DESCRIPTORTYPE, type);
			if (type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
				long img = imagesBase + (long) (ii++) * VkDescriptorImageInfo.SIZEOF;
				MemoryUtil.memPutInt(img + VkDescriptorImageInfo.IMAGELAYOUT, VK_IMAGE_LAYOUT_GENERAL);
				memPutAddress(w + VkWriteDescriptorSet.PIMAGEINFO, img);
				infoAddrs[i] = img;
			} else if (type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
				long buf = buffersBase + (long) (bi++) * VkDescriptorBufferInfo.SIZEOF;
				memPutAddress(w + VkWriteDescriptorSet.PBUFFERINFO, buf);
				infoAddrs[i] = buf;
			} else {
				long tv = texelsBase + (long) (ti++) * Long.BYTES;
				memPutAddress(w + VkWriteDescriptorSet.PTEXELBUFFERVIEW, tv);
				infoAddrs[i] = tv;
			}
		}
	}

	private static int align16(int bytes) {
		return (bytes + 15) & ~15;
	}

	private record NativeFree(long addr) implements Runnable {
		@Override
		public void run() {
			MemoryUtil.nmemFree(addr);
		}
	}

	public void push(VkCommandBuffer commands, long pipelineLayout, VulkanDevice device, VulkanCommandEncoder encoder,
			VulkanDescriptorBindings bindings, Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		if (bindingCount == 0) {
			return;
		}
		if (nativeBlock != 0L && RenderSystem.isOnRenderThread()) {
			pushFast(commands, pipelineLayout, device, encoder, bindings, uniforms, textures);
			return;
		}
		pushStack(commands, pipelineLayout, device, encoder, bindings, uniforms, textures);
	}

	private void pushFast(VkCommandBuffer commands, long pipelineLayout, VulkanDevice device, VulkanCommandEncoder encoder,
			VulkanDescriptorBindings bindings, Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		int count = bindingCount;
		int[] slotArr = slots;
		byte[] typeArr = types;
		long[] addrs = infoAddrs;
		for (int i = 0; i < count; i++) {
			int slot = slotArr[i];
			int type = typeArr[i];
			long target = addrs[i];
			if (type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
				VulkanGpuTextureView view;
				VulkanGpuSampler sampler;
				if (slot >= 0) {
					view = bindings.textureView(slot);
					sampler = bindings.textureSampler(slot);
				} else {
					Vulkan binding = (Vulkan) textures.get(names[i]);
					view = binding == null ? null : binding.ephemera$view();
					sampler = binding == null ? null : binding.ephemera$sampler();
				}
				if (view == null || sampler == null) {
					throw new IllegalStateException("Missing sampler " + names[i]);
				}
				memPutLong(target + VkDescriptorImageInfo.SAMPLER, sampler.vkSampler());
				memPutLong(target + VkDescriptorImageInfo.IMAGEVIEW, view.vkImageView());
			} else {
				GpuBufferSlice slice = slot >= 0 ? bindings.uniform(slot) : uniforms.get(names[i]);
				if (slice == null) {
					String uniformType = type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER ? "UNIFORM_BUFFER" : "TEXEL_BUFFER";
					throw new IllegalStateException("Missing uniform " + names[i] + " (should be " + uniformType + ")");
				}
				if (type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
					memPutLong(target + VkDescriptorBufferInfo.BUFFER, ((VulkanGpuBuffer) slice.buffer()).vkBuffer());
					memPutLong(target + VkDescriptorBufferInfo.OFFSET, slice.offset());
					memPutLong(target + VkDescriptorBufferInfo.RANGE, slice.length());
				} else {
					writeTexelViewNative(device, encoder, slice, texelFormats[i], texelInfoAddr, target);
				}
			}
		}
		nvkCmdPushDescriptorSetKHR(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, count, writesAddr);
	}

	private static void writeTexelViewNative(VulkanDevice device, VulkanCommandEncoder encoder, GpuBufferSlice slice,
			int format, long infoAddr, long outViewAddr) {
		long buffer = ((VulkanGpuBuffer) slice.buffer()).vkBuffer();
		long offset = slice.offset();
		long range = slice.length();
		BufferViewCache cache = VulkanBufferViews.cacheFor(slice, buffer);
		long cachedView = cache == null ? 0L : cache.find(offset, range, format);
		if (cachedView != 0L) {
			memPutLong(outViewAddr, cachedView);
			return;
		}
		memPutLong(infoAddr + VkBufferViewCreateInfo.BUFFER, buffer);
		MemoryUtil.memPutInt(infoAddr + VkBufferViewCreateInfo.FORMAT, format);
		memPutLong(infoAddr + VkBufferViewCreateInfo.OFFSET, offset);
		memPutLong(infoAddr + VkBufferViewCreateInfo.RANGE, range);
		VulkanUtils.crashIfFailure(device, nvkCreateBufferView(device.vkDevice(), infoAddr, 0L, outViewAddr),
			"Couldn't create buffer view for texel buffer");
		long view = MemoryUtil.memGetLong(outViewAddr);
		if (cache != null && !cache.isFull() && view != 0L) {
			cache.remember(offset, range, format, view);
		} else {
			encoder.queueForDestroy(() -> vkDestroyBufferView(device.vkDevice(), view, null));
		}
	}

	private void pushStack(VkCommandBuffer commands, long pipelineLayout, VulkanDevice device, VulkanCommandEncoder encoder,
			VulkanDescriptorBindings bindings, Map<String, GpuBufferSlice> uniforms, Map<String, ?> textures) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(names.length, stack);
			VkDescriptorBufferInfo.Buffer buffers = uniformCount == 0 ? null : VkDescriptorBufferInfo.calloc(uniformCount, stack);
			VkDescriptorImageInfo.Buffer images = samplerCount == 0 ? null : VkDescriptorImageInfo.calloc(samplerCount, stack);
			LongBuffer texelViews = texelCount == 0 ? null : stack.callocLong(texelCount);
			VkBufferViewCreateInfo texelInfo = texelCount == 0 ? null : VkBufferViewCreateInfo.calloc(stack).sType$Default();

			int bufferIndex = 0;
			int imageIndex = 0;
			int texelIndex = 0;
			for (int i = 0; i < names.length; i++) {
				int slot = slots[i];
				int type = types[i];
				VkWriteDescriptorSet write = writes.get().sType$Default().dstBinding(i)
					.descriptorCount(1).descriptorType(type);
				if (type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
					VulkanGpuTextureView view;
					VulkanGpuSampler sampler;
					if (slot >= 0) {
						view = bindings.textureView(slot);
						sampler = bindings.textureSampler(slot);
					} else {
						Vulkan binding = (Vulkan) textures.get(names[i]);
						view = binding == null ? null : binding.ephemera$view();
						sampler = binding == null ? null : binding.ephemera$sampler();
					}
					if (view == null || sampler == null) {
						throw new IllegalStateException("Missing sampler " + names[i]);
					}
					images.position(imageIndex++).sampler(sampler.vkSampler())
						.imageView(view.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
					memPutAddress(write.address() + VkWriteDescriptorSet.PIMAGEINFO, images.address());
					continue;
				}

				GpuBufferSlice slice = slot >= 0 ? bindings.uniform(slot) : uniforms.get(names[i]);
				if (slice == null) {
					String uniformType = type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER ? "UNIFORM_BUFFER" : "TEXEL_BUFFER";
					throw new IllegalStateException("Missing uniform " + names[i] + " (should be " + uniformType + ")");
				}
				if (type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
					buffers.position(bufferIndex++).buffer(((VulkanGpuBuffer) slice.buffer()).vkBuffer())
						.offset(slice.offset()).range(slice.length());
					memPutAddress(write.address() + VkWriteDescriptorSet.PBUFFERINFO, buffers.address());
				} else {
					texelViews.position(texelIndex++);
					writeTexelView(device, encoder, slice, texelFormats[i], texelInfo, texelViews);
					memPutAddress(write.address() + VkWriteDescriptorSet.PTEXELBUFFERVIEW, memAddress(texelViews));
				}
			}
			vkCmdPushDescriptorSetKHR(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, writes.flip());
		}
	}

	private static void writeTexelView(VulkanDevice device, VulkanCommandEncoder encoder, GpuBufferSlice slice,
			int format, VkBufferViewCreateInfo info, LongBuffer result) {
		long buffer = ((VulkanGpuBuffer) slice.buffer()).vkBuffer();
		long offset = slice.offset();
		long range = slice.length();
		BufferViewCache cache = VulkanBufferViews.cacheFor(slice, buffer);
		long cachedView = cache == null ? 0 : cache.find(offset, range, format);
		int index = result.position();
		if (cachedView != 0) {
			result.put(index, cachedView);
			return;
		}

		info.buffer(buffer).offset(offset).range(range).format(format);
		VulkanUtils.crashIfFailure(device, vkCreateBufferView(device.vkDevice(), info, null, result),
			"Couldn't create buffer view for texel buffer");
		long view = result.get(index);
		if (cache != null && !cache.isFull() && view != 0) {
			cache.remember(offset, range, format, view);
		} else {
			encoder.queueForDestroy(() -> vkDestroyBufferView(device.vkDevice(), view, null));
		}
	}
}
