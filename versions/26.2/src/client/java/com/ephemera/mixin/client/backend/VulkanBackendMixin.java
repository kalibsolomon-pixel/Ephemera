package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Modernizes Blaze3D's Vulkan device creation for AMD RDNA 2/3/4 and NVIDIA RTX GPUs:
 * <ul>
 *   <li>Gates pipeline-stalling AMD/NVIDIA diagnostic checkpoints ({@code VK_AMD_buffer_marker}
 *       with {@code BOTTOM_OF_PIPE} PCIe writebacks on every render pass, and
 *       {@code VK_NV_device_diagnostic_checkpoints}) behind explicit debug/validation/RenderDoc
 *       sessions.</li>
 *   <li>Probes and enables {@code VK_EXT_mesh_shader} and Vulkan 1.2 {@code drawIndirectCount}
 *       with fail-soft rollback if device creation rejects any optional extension.</li>
 * </ul>
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
	@WrapOperation(
		method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;hasDeviceExtension(Ljava/lang/String;)Z")
	)
	private boolean ephemera$gateDiagnosticCheckpoints(VulkanPhysicalDevice physicalDevice, String extensionName,
													   Operation<Boolean> original,
													   @Local(argsOnly = true) GpuDebugOptions debugOptions) {
		if (("VK_AMD_buffer_marker".equals(extensionName) || "VK_NV_device_diagnostic_checkpoints".equals(extensionName))
			&& EphemeraCommon.config().backendVulkanModernGpu
			&& !debugOptions.useValidationLayers()
			&& !debugOptions.useLabels()
			&& !"1".equals(System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"))
			&& !Boolean.getBoolean("ephemera.vulkan.checkpoints")) {
			if (original.call(physicalDevice, extensionName)) {
				VulkanModernCapabilities.noteCheckpointsBypassed(extensionName);
			}
			return false;
		}
		return original.call(physicalDevice, extensionName);
	}

	@WrapOperation(
		method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;")
	)
	private VkDevice ephemera$enableModernVulkanFeatures(Collection<String> extensions,
														 VulkanPhysicalDevice physicalDevice,
														 Set<VulkanFeature> features,
														 Operation<VkDevice> original) {
		VulkanModernCapabilities.ProbeResult probe = VulkanModernCapabilities.probeAndEnable(extensions, physicalDevice, features);
		try {
			return original.call(extensions, physicalDevice, features);
		} catch (Throwable t) {
			if (!probe.anyAdded()) {
				throw t;
			}
			VulkanModernCapabilities.LOGGER.warn(
				"vkCreateDevice failed with modern Vulkan extensions enabled; retrying with baseline Vulkan 1.2: {}", t.toString());
			VulkanModernCapabilities.rollbackOptionalExtensions(extensions, features);
			return original.call(extensions, physicalDevice, features);
		}
	}
}
