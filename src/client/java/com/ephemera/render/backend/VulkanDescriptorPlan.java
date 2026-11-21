package com.ephemera.render.backend;

import com.ephemera.core.render.BufferViewCache;
import com.ephemera.render.backend.RenderPassTextureBinding.Vulkan;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK12.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK12.vkCreateBufferView;
import static org.lwjgl.vulkan.VK12.vkDestroyBufferView;

public final class VulkanDescriptorPlan {
	private static final Map<VulkanBindGroupLayout, VulkanDescriptorPlan> PLANS = new WeakHashMap<>();

	private final String[] names;
	private final int[] slots;
	private final byte[] types;
	private final int[] texelFormats;
	private final int uniformCount;
	private final int samplerCount;
	private final int texelCount;

	public static synchronized VulkanDescriptorPlan of(VulkanBindGroupLayout layout) {
		return PLANS.computeIfAbsent(layout, VulkanDescriptorPlan::new);
	}

	private VulkanDescriptorPlan(VulkanBindGroupLayout layout) {
		List<VulkanBindGroupLayout.Entry> entries = layout.entries();
		int count = entries.size();
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
	}

	public void push(VkCommandBuffer commands, long pipelineLayout, VulkanDevice device, VulkanCommandEncoder encoder,
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
					Vulkan binding = slot >= 0 ? bindings.texture(slot) : (Vulkan) textures.get(names[i]);
					if (binding == null) {
						throw new IllegalStateException("Missing sampler " + names[i]);
					}
					images.position(imageIndex++).sampler(binding.ephemera$sampler().vkSampler())
						.imageView(binding.ephemera$view().vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
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
