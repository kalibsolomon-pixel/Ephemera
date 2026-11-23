package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 device creation ({@code FeatureSet} based): the same two changes as on 26.2.
 * <ul>
 *   <li>Drops the pipeline-stalling AMD/NVIDIA diagnostic checkpoint feature sets, which vanilla
 *       enables whenever supported, unless a debug/validation/RenderDoc session asks for them.</li>
 *   <li>Adds {@code VK_EXT_mesh_shader} and Vulkan 1.2 {@code drawIndirectCount} as an extra
 *       feature set, retrying without it if device creation rejects it.</li>
 * </ul>
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
	private static final String CREATE_DEVICE =
		"createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;";

	@WrapOperation(
		method = CREATE_DEVICE,
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanFeatureSets;optionalFeatureSets()Ljava/util/Set;")
	)
	private Set<FeatureSet> ephemera$gateDiagnosticCheckpoints(Operation<Set<FeatureSet>> original,
															   @Local(argsOnly = true) GpuDebugOptions debugOptions) {
		Set<FeatureSet> sets = original.call();
		if (!EphemeraCommon.config().backendVulkanModernGpu
			|| debugOptions.useValidationLayers()
			|| debugOptions.useLabels()
			|| "1".equals(System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"))
			|| Boolean.getBoolean("ephemera.vulkan.checkpoints")) {
			return sets;
		}
		Set<FeatureSet> gated = new ObjectOpenHashSet<>(sets);
		if (gated.remove(VulkanFeatureSets.AMD_BUFFER_MARKER_FEATURESET)) {
			VulkanModernCapabilities.noteCheckpointsBypassed("VK_AMD_buffer_marker");
		}
		if (gated.remove(VulkanFeatureSets.NV_DIAGNOSTIC_CHECKPOINT_FEATURESET)) {
			VulkanModernCapabilities.noteCheckpointsBypassed("VK_NV_device_diagnostic_checkpoints");
		}
		return gated;
	}

	@WrapOperation(
		method = CREATE_DEVICE,
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;")
	)
	private VkDevice ephemera$enableModernVulkanFeatures(FeatureSet enabled, VulkanPhysicalDevice physicalDevice,
														 Operation<VkDevice> original) {
		Set<String> extensions = new ObjectOpenHashSet<>();
		Set<VulkanFeature> features = new ObjectOpenHashSet<>();
		VulkanModernCapabilities.ProbeResult probe = VulkanModernCapabilities.probeAndEnable(extensions, physicalDevice, features);
		if (!probe.anyAdded()) {
			return original.call(enabled, physicalDevice);
		}
		try {
			return original.call(enabled.composite(new FeatureSet("Ephemera optional", extensions, features)), physicalDevice);
		} catch (Throwable t) {
			VulkanModernCapabilities.LOGGER.warn(
				"vkCreateDevice failed with Ephemera's optional Vulkan features enabled; retrying without them: {}", t.toString());
			VulkanModernCapabilities.rollbackOptionalExtensions(extensions, features);
			return original.call(enabled, physicalDevice);
		}
	}
}
