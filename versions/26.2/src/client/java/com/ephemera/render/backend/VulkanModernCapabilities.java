package com.ephemera.render.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupSizeControlProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardware capabilities detected during Vulkan device creation for modern AMD (RDNA 2/3/4)
 * and NVIDIA (Turing/Ampere/Ada/Blackwell) GPUs.
 *
 * <p>Populated once on the render thread inside {@code VulkanBackend.createDevice}; safe to
 * read from any thread thereafter via volatile publication.
 */
public final class VulkanModernCapabilities {
	public static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/Vulkan");

	public static final int VENDOR_AMD = 0x1002;
	public static final int VENDOR_NVIDIA = 0x10DE;
	public static final int VENDOR_INTEL = 0x8086;

	public static final VulkanPNextStruct MESH_SHADER_FEATURES_STRUCT = new VulkanPNextStruct(
		EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
		VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF
	);
	public static final VulkanFeature MESH_SHADER_FEATURE = new VulkanFeature(
		MESH_SHADER_FEATURES_STRUCT, "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER
	);
	public static final VulkanFeature TASK_SHADER_FEATURE = new VulkanFeature(
		MESH_SHADER_FEATURES_STRUCT, "taskShader", VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER
	);
	public static final VulkanFeature DRAW_INDIRECT_COUNT_FEATURE = new VulkanFeature(
		VulkanBackend.VK12_FEATURES_STRUCT, "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT
	);

	private static volatile Snapshot current = Snapshot.INACTIVE;
	private static volatile boolean checkpointsBypassed;
	private static volatile String bypassedCheckpointExt;
	private static volatile boolean meshEngineActive;

	public record Snapshot(
		boolean initialized,
		int vendorId,
		String deviceName,
		int apiVersion,
		boolean modernSyncEnabled,
		boolean checkpointsBypassed,
		boolean meshShaderEnabled,
		boolean taskShaderEnabled,
		boolean drawIndirectCountEnabled,
		boolean computeSubgroupBallot,
		boolean taskSubgroupBallot,
		int preferredMeshWorkGroupSize,
		int maxMeshOutputVertices,
		int maxMeshOutputPrimitives,
		int maxMeshWorkGroupCountX
	) {
		static final Snapshot INACTIVE = new Snapshot(
			false, 0, "", 0, false, false, false, false, false, false, false, 0, 0, 0, 0
		);

		public String vendorLabel() {
			return switch (vendorId) {
				case VENDOR_AMD -> "AMD";
				case VENDOR_NVIDIA -> "NVIDIA";
				case VENDOR_INTEL -> "Intel";
				default -> String.format("0x%04X", vendorId);
			};
		}
	}

	private VulkanModernCapabilities() {}

	public static Snapshot get() {
		return current;
	}

	public static boolean isVulkanActive() {
		return current.initialized();
	}

	public static boolean modernSyncActive() {
		return current.initialized() && current.modernSyncEnabled() && EphemeraCommon.config().backendVulkanModernGpu;
	}

	/** True when {@code VK_EXT_mesh_shader} was enabled on the Vulkan device and config allows it. */
	public static boolean meshShaderSupportedAndEnabled() {
		EphemeraConfig cfg = EphemeraCommon.config();
		return current.initialized() && current.meshShaderEnabled()
			&& cfg.terrainEngineEnabled && cfg.terrainVulkanMeshShaders;
	}

	public static boolean isMeshEngineActive() {
		return meshEngineActive;
	}

	public static void setMeshEngineActive(boolean active) {
		meshEngineActive = active;
	}

	public static void noteCheckpointsBypassed(String extensionName) {
		checkpointsBypassed = true;
		bypassedCheckpointExt = extensionName;
	}

	/**
	 * Probes optional modern GPU extensions/features on {@code physicalDevice} and adds the
	 * supported ones to {@code extensions} and {@code features} prior to {@code vkCreateDevice}.
	 */
	public static ProbeResult probeAndEnable(Collection<String> extensions,
											 VulkanPhysicalDevice physicalDevice,
											 Set<VulkanFeature> features) {
		EphemeraConfig cfg = EphemeraCommon.config();
		VkPhysicalDevice vkPhys = physicalDevice.vkPhysicalDevice();
		VkPhysicalDeviceProperties props = physicalDevice.vkPhysicalDeviceProperties();
		int vendorId = props.vendorID();
		String deviceName = physicalDevice.deviceName();
		int apiVersion = props.apiVersion();

		boolean meshEnabled = false;
		boolean taskEnabled = false;
		boolean drawIndirectCount = false;
		boolean subgroupBallot = false;
		boolean taskSubgroupBallot = false;
		int preferredMeshWg = 32;
		int maxMeshVerts = 0;
		int maxMeshPrims = 0;
		int maxMeshWgX = 0;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Query Vulkan 1.1 subgroup properties and optional VK_EXT_mesh_shader properties
			boolean hasMeshExt = cfg.terrainEngineEnabled && cfg.terrainVulkanMeshShaders
				&& physicalDevice.hasDeviceExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);

			VkPhysicalDeviceSubgroupProperties subgroupProps = VkPhysicalDeviceSubgroupProperties.calloc(stack)
				.sType$Default();
			boolean hasVk13 = apiVersion >= VK13.VK_API_VERSION_1_3;
			VkPhysicalDeviceSubgroupSizeControlProperties subgroupSizeCtrl = hasVk13
				? VkPhysicalDeviceSubgroupSizeControlProperties.calloc(stack).sType$Default()
				: null;
			VkPhysicalDeviceMeshShaderPropertiesEXT meshProps = hasMeshExt
				? VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack).sType$Default()
				: null;

			VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
			props2.pNext(subgroupProps);
			if (subgroupSizeCtrl != null) {
				subgroupProps.pNext(subgroupSizeCtrl.address());
			}
			if (meshProps != null) {
				if (subgroupSizeCtrl != null) {
					subgroupSizeCtrl.pNext(meshProps.address());
				} else {
					subgroupProps.pNext(meshProps.address());
				}
			}
			VK12.vkGetPhysicalDeviceProperties2(vkPhys, props2);

			int requiredSubgroupOps = VK11.VK_SUBGROUP_FEATURE_BASIC_BIT | VK11.VK_SUBGROUP_FEATURE_BALLOT_BIT;
			subgroupBallot = (subgroupProps.supportedStages() & VK12.VK_SHADER_STAGE_COMPUTE_BIT) != 0
				&& (subgroupProps.supportedOperations() & requiredSubgroupOps) == requiredSubgroupOps;
			// A 32-lane Task workgroup only fits in one subgroup when minSubgroupSize >= 32
			// (AMD RDNA Wave32/64, NVIDIA Warp32). GPUs with SIMD8/16 minSubgroupSize (e.g. Intel Arc)
			// can split a 32-lane workgroup across multiple subgroups and must use the shared-atomic path.
			int minSubgroup = subgroupSizeCtrl != null && subgroupSizeCtrl.minSubgroupSize() > 0
				? subgroupSizeCtrl.minSubgroupSize()
				: (vendorId == VENDOR_INTEL ? 8 : subgroupProps.subgroupSize());
			taskSubgroupBallot = (subgroupProps.supportedStages() & EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT) != 0
				&& (subgroupProps.supportedOperations() & requiredSubgroupOps) == requiredSubgroupOps
				&& subgroupProps.subgroupSize() >= 32
				&& minSubgroup >= 32;

			// Query Vulkan 1.2 drawIndirectCount + VK_EXT_mesh_shader features
			VkPhysicalDeviceVulkan12Features vk12Feats = VkPhysicalDeviceVulkan12Features.calloc(stack)
				.sType$Default();
			VkPhysicalDeviceMeshShaderFeaturesEXT meshFeats = hasMeshExt
				? VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack).sType$Default()
				: null;
			VkPhysicalDeviceFeatures2 feats2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			feats2.pNext(vk12Feats);
			if (meshFeats != null) {
				vk12Feats.pNext(meshFeats.address());
			}
			VK12.vkGetPhysicalDeviceFeatures2(vkPhys, feats2);

			if (cfg.backendVulkanModernGpu && vk12Feats.drawIndirectCount()) {
				features.add(DRAW_INDIRECT_COUNT_FEATURE);
				drawIndirectCount = true;
			}

			if (hasMeshExt && meshFeats != null && meshProps != null && meshFeats.meshShader()) {
				maxMeshVerts = meshProps.maxMeshOutputVertices();
				maxMeshPrims = meshProps.maxMeshOutputPrimitives();
				maxMeshWgX = meshProps.maxMeshWorkGroupCount(0);
				int maxInvoc = meshProps.maxMeshWorkGroupInvocations();
				int pref = meshProps.maxPreferredMeshWorkGroupInvocations();
				preferredMeshWg = pref > 0 ? Math.min(pref, maxInvoc) : Math.min(32, maxInvoc);

				// Our 32-quad terrain meshlets need 128 vertices, 64 triangles, and 32 invocations
				if (maxMeshVerts >= 128 && maxMeshPrims >= 64 && maxInvoc >= 32 && maxMeshWgX >= 65535) {
					extensions.add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
					features.add(MESH_SHADER_FEATURE);
					meshEnabled = true;
					if (meshFeats.taskShader()) {
						features.add(TASK_SHADER_FEATURE);
						taskEnabled = true;
					}
				} else {
					LOGGER.info("Skipping VK_EXT_mesh_shader on {}: insufficient limits (verts={}, prims={}, invoc={}, wgX={})",
						deviceName, maxMeshVerts, maxMeshPrims, maxInvoc, maxMeshWgX);
				}
			}
		} catch (Throwable t) {
			LOGGER.warn("Failed to probe modern Vulkan GPU extensions; continuing with standard Vulkan 1.2: {}", t.toString());
			extensions.remove(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
			features.remove(MESH_SHADER_FEATURE);
			features.remove(TASK_SHADER_FEATURE);
			features.remove(DRAW_INDIRECT_COUNT_FEATURE);
			meshEnabled = false;
			taskEnabled = false;
			drawIndirectCount = false;
			taskSubgroupBallot = false;
		}

		Snapshot snap = new Snapshot(
			true, vendorId, deviceName, apiVersion,
			cfg.backendVulkanModernGpu, checkpointsBypassed,
			meshEnabled, taskEnabled, drawIndirectCount, subgroupBallot, taskSubgroupBallot,
			preferredMeshWg, maxMeshVerts, maxMeshPrims, maxMeshWgX
		);
		current = snap;
		meshEngineActive = false;

		LOGGER.info("Modern Vulkan GPU init [{} / {}]: modernSync={}, checkpointsBypassed={}({}), meshShader={} (task={}, prefWG={}, maxV={}, maxP={}), drawIndirectCount={}, subgroups={}, taskBallot={}",
			snap.vendorLabel(), deviceName, snap.modernSyncEnabled(), snap.checkpointsBypassed(),
			bypassedCheckpointExt != null ? bypassedCheckpointExt : "none",
			meshEnabled, taskEnabled, preferredMeshWg, maxMeshVerts, maxMeshPrims,
			drawIndirectCount, subgroupBallot, taskSubgroupBallot);

		return new ProbeResult(meshEnabled, taskEnabled, drawIndirectCount);
	}

	/** Called if vkCreateDevice fails and we retry without optional modern extensions. */
	public static void rollbackOptionalExtensions(Collection<String> extensions, Set<VulkanFeature> features) {
		extensions.remove(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
		features.remove(MESH_SHADER_FEATURE);
		features.remove(TASK_SHADER_FEATURE);
		features.remove(DRAW_INDIRECT_COUNT_FEATURE);
		Snapshot old = current;
		current = new Snapshot(
			old.initialized(), old.vendorId(), old.deviceName(), old.apiVersion(),
			old.modernSyncEnabled(), old.checkpointsBypassed(),
			false, false, false, old.computeSubgroupBallot(), false, 0, 0, 0, 0
		);
		meshEngineActive = false;
	}

	public record ProbeResult(boolean meshEnabled, boolean taskEnabled, boolean drawIndirectCount) {
		public boolean anyAdded() {
			return meshEnabled || taskEnabled || drawIndirectCount;
		}
	}
}
