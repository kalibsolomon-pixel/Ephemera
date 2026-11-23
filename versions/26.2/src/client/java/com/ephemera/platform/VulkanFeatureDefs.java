package com.ephemera.platform;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/** Optional Vulkan device features Ephemera enables, built with the 26.2 feature-struct API. */
public final class VulkanFeatureDefs {
	private static final VulkanPNextStruct MESH_SHADER_FEATURES = new VulkanPNextStruct(
		EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
		VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF
	);
	public static final VulkanFeature MESH_SHADER = new VulkanFeature(
		MESH_SHADER_FEATURES, "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER
	);
	public static final VulkanFeature TASK_SHADER = new VulkanFeature(
		MESH_SHADER_FEATURES, "taskShader", VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER
	);
	public static final VulkanFeature DRAW_INDIRECT_COUNT = new VulkanFeature(
		VulkanBackend.VK12_FEATURES_STRUCT, "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT
	);

	private VulkanFeatureDefs() {}
}
