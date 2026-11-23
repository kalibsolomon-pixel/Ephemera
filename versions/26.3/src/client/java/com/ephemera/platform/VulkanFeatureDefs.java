package com.ephemera.platform;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/** Optional Vulkan device features Ephemera enables, built with the 26.3 feature-struct API. */
public final class VulkanFeatureDefs {
	private static final VulkanPNextStruct MESH_SHADER_FEATURES = new VulkanPNextStruct(VkPhysicalDeviceMeshShaderFeaturesEXT.class);
	public static final VulkanFeature MESH_SHADER = new VulkanFeature(
		MESH_SHADER_FEATURES, "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER
	);
	public static final VulkanFeature TASK_SHADER = new VulkanFeature(
		MESH_SHADER_FEATURES, "taskShader", VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER
	);
	public static final VulkanFeature DRAW_INDIRECT_COUNT = new VulkanFeature(
		VulkanFeatureSets.VK12_FEATURES_STRUCT, "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT
	);

	private VulkanFeatureDefs() {}
}
