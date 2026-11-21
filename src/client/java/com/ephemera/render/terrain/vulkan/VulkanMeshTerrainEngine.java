package com.ephemera.render.terrain.vulkan;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.terrain.BakedSectionMeshlets;
import com.ephemera.core.terrain.VulkanFrustumMath;
import com.ephemera.core.visuals.ShaderSourcePatcher;
import com.ephemera.mixin.client.backend.CommandEncoderBackendAccessor;
import com.ephemera.mixin.client.backend.RenderPassBackendAccessor;
import com.ephemera.render.backend.VulkanEncoderBridge;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.ephemera.visuals.VisualsRuntime;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nvidium-class cross-vendor {@code VK_EXT_mesh_shader} terrain renderer for modern AMD
 * (RDNA 2/3/4 — RX 9070 XT) and NVIDIA (RTX 20/30/40/50) GPUs on Minecraft 26.2's native Vulkan
 * Blaze3D backend.
 *
 * <p>On GPUs supporting hardware Task + Mesh shaders (RX 9070 XT, RTX 20/30/40/50), runs a pure
 * single-pass {@code Task + Mesh} graphics pipeline with ReBAR host-visible ring buffers:
 * zero compute pass, zero compute-to-graphics barrier, zero {@code visibleMeshlets} VRAM traffic,
 * zero texture fetches in the Mesh Shader stage, and ONE single {@code vkCmdDrawMeshTasksEXT}
 * per opaque layer (sharing ONE push-descriptor set across SOLID and CUTOUT).
 */
public final class VulkanMeshTerrainEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("Ephemera/VulkanMesh");
	private static final VulkanMeshTerrainEngine INSTANCE = new VulkanMeshTerrainEngine();

	private static final int[] INITIAL_INDIRECT_WORDS = {0, 1, 1, 0, 1, 1};

	private @Nullable VulkanDevice device;
	private @Nullable VulkanMeshArena arena;
	private boolean taskShaderMode;

	// Compute cull pipeline (fallback only when !taskShaderMode)
	private long computeSetLayout;
	private long computePipelineLayout;
	private long computePipeline;

	// Task + Mesh + Fragment graphics pipelines (SOLID = 0, CUTOUT = 1)
	private long meshSetLayout;
	private long meshPipelineLayout;
	private long taskShaderModule;
	private long meshShaderModule;
	private long solidPipeline;
	private long cutoutPipeline;
	private int compiledColorFormat;
	private int compiledDepthFormat;
	private ShaderSourcePatcher.@Nullable Options compiledVisualOptions;

	// Reusable per-frame scratch (zero GC)
	private final Matrix4f clipMatrix = new Matrix4f();
	private final float[] clipFloats = new float[16];
	private final float[] frustumPlanes = new float[VulkanFrustumMath.PLANE_FLOATS];
	private final ByteBuffer indirectResetBuf = ByteBuffer.allocateDirect(VulkanMeshArena.INDIRECT_CMD_BYTES).order(ByteOrder.nativeOrder());

	private int frameSolidTasks;
	private int frameCutoutTasks;
	private int frameActiveLayerMask;
	private boolean failed;

	private VulkanMeshTerrainEngine() {}

	public static VulkanMeshTerrainEngine get() {
		return INSTANCE;
	}

	/** True when {@code VK_EXT_mesh_shader} is enabled, ready, and no external shaders are active. */
	public boolean isAvailable() {
		if (failed || !VulkanModernCapabilities.meshShaderSupportedAndEnabled() || VisualsRuntime.externalShadersActive()) {
			return false;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		return ((CommandEncoderBackendAccessor) encoder).ephemera$backend() instanceof VulkanEncoderBridge;
	}

	public boolean usesTaskShader() {
		return taskShaderMode;
	}

	/** Called at the start of {@code prepareRegional} / {@code reuseRegional} before any uploads. */
	public boolean beginFrame() {
		if (!isAvailable()) {
			frameSolidTasks = 0;
			frameCutoutTasks = 0;
			frameActiveLayerMask = 0;
			return false;
		}
		try {
			VulkanEncoderBridge bridge = currentBridge();
			if (bridge == null) {
				return false;
			}
			ensureInitialized(bridge.ephemera$device(), (VulkanCommandEncoder) bridge);
			arena.beginFrame();
			frameSolidTasks = 0;
			frameCutoutTasks = 0;
			frameActiveLayerMask = 0;
			return true;
		} catch (Throwable t) {
			disable(t);
			return false;
		}
	}

	public int arenaEpoch() {
		return arena != null ? arena.epoch() : -1;
	}

	public boolean isValidHandle(@Nullable VulkanMeshSectionHandle handle) {
		return handle != null && !handle.released && arena != null && handle.arenaEpoch == arena.epoch();
	}

	public @Nullable VulkanMeshSectionHandle uploadSection(BakedSectionMeshlets baked, float visibility) {
		if (failed || arena == null) {
			return null;
		}
		try {
			VulkanEncoderBridge bridge = currentBridge();
			if (bridge == null) {
				return null;
			}
			return arena.uploadSection((VulkanCommandEncoder) bridge, bridge, baked, visibility);
		} catch (Throwable t) {
			disable(t);
			return null;
		}
	}

	public void releaseSection(@Nullable VulkanMeshSectionHandle handle) {
		if (handle != null && arena != null && !failed) {
			arena.deferFreeSection(handle);
		}
	}

	public void resetSections() {
		frameSolidTasks = 0;
		frameCutoutTasks = 0;
		frameActiveLayerMask = 0;
		if (arena != null) {
			arena.resetAllSections();
		}
	}

	/**
	 * Prepares per-frame GPU culling uniforms & task lists directly in mapped ReBAR ring buffers.
	 * In Task+Mesh mode: zero compute dispatch, zero compute->mesh barrier, zero transfer barriers
	 * on steady frames! In compute fallback mode: runs the compute culling dispatch.
	 */
	public void prepareFrame(int[] solidTasks, int solidTaskCount,
							 int[] cutoutTasks, int cutoutTaskCount,
							 long taskListVersion, int activeLayerMask,
							 int[] visUpdateSlots, float[] visUpdateValues, int visUpdateCount,
							 Matrix4fc modelView, Matrix4fc projection,
							 int camBlockX, int camBlockY, int camBlockZ,
							 float camOffX, float camOffY, float camOffZ,
							 int atlasW, int atlasH,
							 boolean fogCull, float envEnd, float rdEnd, boolean dirCull) {
		frameSolidTasks = 0;
		frameCutoutTasks = 0;
		frameActiveLayerMask = 0;
		int totalTasks = solidTaskCount + cutoutTaskCount;
		if (failed || arena == null || totalTasks <= 0 || activeLayerMask == 0) {
			return;
		}
		try {
			VulkanEncoderBridge bridge = currentBridge();
			if (bridge == null) {
				return;
			}
			VulkanCommandEncoder encoder = (VulkanCommandEncoder) bridge;

			if (visUpdateCount > 0) {
				arena.uploadVisibilityUpdates(encoder, bridge, visUpdateSlots, visUpdateValues, visUpdateCount);
			}
			arena.writeTaskLists(encoder, solidTasks, solidTaskCount, cutoutTasks, cutoutTaskCount, taskListVersion);

			// Extract 6 Vulkan frustum planes from projection * modelView (camera-relative space)
			boolean gpuFrustumCull = EphemeraCommon.config().terrainVulkanGpuCulling && projection != null;
			if (gpuFrustumCull) {
				clipMatrix.set(projection).mul(modelView);
				clipMatrix.get(clipFloats);
				VulkanFrustumMath.extractPlanes(clipFloats, true, frustumPlanes);
			}

			// Write 256-byte CullParams UBO straight into mapped ReBAR ring buffer (zero vkCmdUpdateBuffer!)
			VulkanMeshArena.GpuBuf cullParams = arena.cullParamsBuf();
			long cp = cullParams.mappedPtr;
			for (int i = 0; i < VulkanFrustumMath.PLANE_FLOATS; i++) {
				MemoryUtil.memPutFloat(cp + ((long) i << 2), gpuFrustumCull ? frustumPlanes[i] : 0.0f);
			}
			MemoryUtil.memPutInt(cp + 96L, camBlockX);
			MemoryUtil.memPutInt(cp + 100L, camBlockY);
			MemoryUtil.memPutInt(cp + 104L, camBlockZ);
			MemoryUtil.memPutInt(cp + 108L, solidTaskCount);
			MemoryUtil.memPutFloat(cp + 112L, camOffX);
			MemoryUtil.memPutFloat(cp + 116L, camOffY);
			MemoryUtil.memPutFloat(cp + 120L, camOffZ);
			MemoryUtil.memPutFloat(cp + 124L, envEnd);
			MemoryUtil.memPutFloat(cp + 128L, rdEnd);
			MemoryUtil.memPutFloat(cp + 132L, fogCull ? 1.0f : 0.0f);
			MemoryUtil.memPutFloat(cp + 136L, dirCull ? 1.0f : 0.0f);
			MemoryUtil.memPutFloat(cp + 140L, 0.5f);
			MemoryUtil.memPutInt(cp + 144L, VulkanMeshArena.MAX_VISIBLE_MESHLETS_PER_LAYER);
			MemoryUtil.memPutInt(cp + 148L, gpuFrustumCull ? 1 : 0);
			MemoryUtil.memPutInt(cp + 152L, 0);
			MemoryUtil.memPutInt(cp + 156L, 0);
			arena.flushAllocation(cullParams, VulkanMeshArena.CULL_PARAMS_BYTES);

			// Write 80-byte MeshFrame UBO straight into mapped ReBAR ring buffer
			VulkanMeshArena.GpuBuf meshFrame = arena.meshFrameBuf();
			long mf = meshFrame.mappedPtr;
			modelView.get(clipFloats);
			for (int i = 0; i < 16; i++) {
				MemoryUtil.memPutFloat(mf + ((long) i << 2), clipFloats[i]);
			}
			MemoryUtil.memPutInt(mf + 64L, atlasW);
			MemoryUtil.memPutInt(mf + 68L, atlasH);
			MemoryUtil.memPutInt(mf + 72L, 0);
			MemoryUtil.memPutInt(mf + 76L, 0);
			arena.flushAllocation(meshFrame, VulkanMeshArena.MESH_FRAME_BYTES);

			if (!taskShaderMode) {
				VkCommandBuffer cmd = bridge.ephemera$commandBuffer();
				VulkanMeshArena.GpuBuf indirect = arena.indirectCmdBuf();
				indirectResetBuf.clear();
				for (int w : INITIAL_INDIRECT_WORDS) {
					indirectResetBuf.putInt(w);
				}
				indirectResetBuf.flip();
				VK12.vkCmdUpdateBuffer(cmd, indirect.buffer, 0L, indirectResetBuf);
				bridge.ephemera$markTransferWrite();
				bridge.ephemera$flushBarriers();

				VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
				pushComputeDescriptors(cmd, cullParams, arena.visibleTasksBuf(), arena.sectionMetaBuf,
					arena.meshletBuf, arena.visibleMeshletsBuf(), indirect);
				VK12.vkCmdDispatch(cmd, solidTaskCount, 1, 1);
				bridge.ephemera$barrierComputeToMeshDraw();
			}

			frameSolidTasks = solidTaskCount;
			frameCutoutTasks = cutoutTaskCount;
			frameActiveLayerMask = activeLayerMask;
		} catch (Throwable t) {
			disable(t);
		}
	}

	public boolean hasLayerDraw(ChunkSectionLayer layer) {
		if (failed || arena == null) {
			return false;
		}
		int ord = layer.ordinal();
		if (ord >= 2 || (frameActiveLayerMask & (1 << ord)) == 0) {
			return false;
		}
		if (taskShaderMode) {
			return ord == 0 ? frameSolidTasks > 0 : frameCutoutTasks > 0;
		}
		return frameSolidTasks > 0;
	}

	/**
	 * Executes ONE single GPU-driven {@code vkCmdDrawMeshTasksEXT} (Task+Mesh) or
	 * {@code vkCmdDrawMeshTasksIndirectEXT} (compute fallback) call for the given opaque terrain
	 * layer directly inside Blaze3D's open {@link RenderPass}.
	 *
	 * @param descriptorsAlreadyPushed true when an earlier mesh layer in the same render pass
	 *                                 already pushed the shared descriptor set
	 */
	public boolean drawLayer(RenderPass pass, ChunkSectionLayer layer, RenderTarget target,
							 GpuTextureView atlas, GpuSampler atlasSampler,
							 GpuTextureView lightmap, GpuSampler lightmapSampler,
							 boolean descriptorsAlreadyPushed) {
		if (!hasLayerDraw(layer)) {
			return false;
		}
		try {
			if (!(((RenderPassBackendAccessor) pass).ephemera$backend() instanceof VulkanRenderPassBridge rp)) {
				return false;
			}
			if (!rp.ephemera$hasDepth() || rp.ephemera$device() != device) {
				return false;
			}
			int colorFmt = VulkanConst.toVk(target.getColorTexture().getFormat());
			int depthFmt = VulkanConst.toVk(target.getDepthTexture().getFormat());
			ensureGraphicsPipelines(rp.ephemera$device(), rp.ephemera$encoder(), colorFmt, depthFmt);

			long vkPipeline = layer == ChunkSectionLayer.SOLID ? solidPipeline : cutoutPipeline;
			if (vkPipeline == 0L) {
				return false;
			}

			VkCommandBuffer cmd = rp.ephemera$commandBuffer();
			VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, vkPipeline);

			if (!descriptorsAlreadyPushed) {
				GpuBufferSlice projSlice = RenderSystem.getProjectionMatrixBuffer();
				GpuBufferSlice fogSlice = RenderSystem.getShaderFog();
				GpuBuffer globalsBuf = RenderSystem.getGlobalSettingsUniform();
				if (projSlice == null || fogSlice == null || globalsBuf == null) {
					return false;
				}
				pushGraphicsDescriptors(cmd, projSlice, fogSlice, globalsBuf.slice(),
					arena.meshFrameBuf(), arena.cullParamsBuf(),
					(VulkanGpuTextureView) atlas, (VulkanGpuSampler) atlasSampler,
					(VulkanGpuTextureView) lightmap, (VulkanGpuSampler) lightmapSampler);
			}

			int layerIdx = layer == ChunkSectionLayer.SOLID ? 0 : 1;
			if (taskShaderMode) {
				int taskOffset = layerIdx == 0 ? 0 : frameSolidTasks;
				int taskCount = layerIdx == 0 ? frameSolidTasks : frameCutoutTasks;
				try (MemoryStack stack = MemoryStack.stackPush()) {
					IntBuffer pc = stack.ints(taskOffset, layerIdx);
					VK12.vkCmdPushConstants(cmd, meshPipelineLayout, EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT, 0, pc);
				}
				EXTMeshShader.vkCmdDrawMeshTasksEXT(cmd, taskCount, 1, 1);
			} else {
				int visOffset = layerIdx * VulkanMeshArena.MAX_VISIBLE_MESHLETS_PER_LAYER;
				try (MemoryStack stack = MemoryStack.stackPush()) {
					IntBuffer pc = stack.ints(visOffset, VulkanMeshArena.MAX_VISIBLE_MESHLETS_PER_LAYER);
					VK12.vkCmdPushConstants(cmd, meshPipelineLayout, EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, pc);
				}
				EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, arena.indirectCmdBuf().buffer, layerIdx * 12L, 1, 12);
			}

			rp.ephemera$invalidateAfterExternalDraw();
			return true;
		} catch (Throwable t) {
			disable(t);
			return false;
		}
	}

	private void ensureInitialized(VulkanDevice vkDev, VulkanCommandEncoder encoder) {
		if (device == vkDev && arena != null && meshShaderModule != 0L) {
			return;
		}
		if (device != null && device != vkDev) {
			arena = null;
			computeSetLayout = 0L;
			computePipelineLayout = 0L;
			computePipeline = 0L;
			meshSetLayout = 0L;
			meshPipelineLayout = 0L;
			taskShaderModule = 0L;
			meshShaderModule = 0L;
			solidPipeline = 0L;
			cutoutPipeline = 0L;
		}
		device = vkDev;
		taskShaderMode = VulkanModernCapabilities.get().taskShaderEnabled();
		arena = new VulkanMeshArena(vkDev, taskShaderMode);
		if (!taskShaderMode) {
			createComputePipeline(vkDev);
		}
		createMeshLayoutsAndModules(vkDev);
		VulkanModernCapabilities.setMeshEngineActive(true);
		LOGGER.info("Initialized Nvidium-class VK_EXT_mesh_shader terrain engine on {} (mode={}, 64 MB VRAM quad pool, ReBAR ring buffers, 32-quad Wave32/Warp32 meshlets)",
			VulkanModernCapabilities.get().deviceName(),
			taskShaderMode ? "Task+Mesh single-pass" : "Compute+Mesh fallback");
	}

	private void createComputePipeline(VulkanDevice vkDev) {
		VkDevice dev = vkDev.vkDevice();
		long compModule = VulkanMeshShaders.compileShaderModule(dev, "ephemera_cull_meshlets.comp",
			VulkanMeshShaders.CULL_COMPUTE_GLSL, Shaderc.shaderc_compute_shader);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
			bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
			for (int i = 1; i <= 5; i++) {
				bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
			}
			VkDescriptorSetLayoutCreateInfo dsInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
				.pBindings(bindings);
			LongBuffer out = stack.callocLong(1);
			checkVk(VK12.vkCreateDescriptorSetLayout(dev, dsInfo, null, out), "vkCreateDescriptorSetLayout(compute)");
			computeSetLayout = out.get(0);

			VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(computeSetLayout));
			checkVk(VK12.vkCreatePipelineLayout(dev, plInfo, null, out), "vkCreatePipelineLayout(compute)");
			computePipelineLayout = out.get(0);

			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
				.sType$Default()
				.stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
				.module(compModule)
				.pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo.Buffer cpInfo = VkComputePipelineCreateInfo.calloc(1, stack)
				.sType$Default()
				.stage(stage)
				.layout(computePipelineLayout);
			checkVk(VK12.vkCreateComputePipelines(dev, 0L, cpInfo, null, out), "vkCreateComputePipelines");
			computePipeline = out.get(0);
		} finally {
			VK12.vkDestroyShaderModule(dev, compModule, null);
		}
	}

	private void createMeshLayoutsAndModules(VulkanDevice vkDev) {
		VkDevice dev = vkDev.vkDevice();
		if (taskShaderMode) {
			taskShaderModule = VulkanMeshShaders.compileShaderModule(dev, "ephemera_terrain.task",
				VulkanMeshShaders.TERRAIN_TASK_GLSL, Shaderc.shaderc_task_shader);
			meshShaderModule = VulkanMeshShaders.compileShaderModule(dev, "ephemera_terrain_task.mesh",
				VulkanMeshShaders.TERRAIN_MESH_TASK_GLSL, Shaderc.shaderc_mesh_shader);
		} else {
			meshShaderModule = VulkanMeshShaders.compileShaderModule(dev, "ephemera_terrain_compute.mesh",
				VulkanMeshShaders.TERRAIN_MESH_COMPUTE_GLSL, Shaderc.shaderc_mesh_shader);
		}

		int taskStage = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
		int meshStage = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
		int fragStage = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
		int geomStages = taskShaderMode ? (taskStage | meshStage) : meshStage;
		int bindingCount = taskShaderMode ? 11 : 10;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
			bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(taskShaderMode ? taskStage : meshStage);
			bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(fragStage);
			bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(taskShaderMode ? fragStage : (meshStage | fragStage));
			bindings.get(3).binding(3).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags((taskShaderMode ? taskStage : meshStage) | fragStage);
			bindings.get(4).binding(4).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(fragStage);
			bindings.get(5).binding(5).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(fragStage);
			bindings.get(6).binding(6).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(meshStage);
			bindings.get(7).binding(7).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(geomStages);
			bindings.get(8).binding(8).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(taskShaderMode ? taskStage : meshStage);
			bindings.get(9).binding(9).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(taskShaderMode ? taskStage : meshStage);
			if (taskShaderMode) {
				bindings.get(10).binding(10).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(taskStage);
			}

			VkDescriptorSetLayoutCreateInfo dsInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
				.pBindings(bindings);
			LongBuffer out = stack.callocLong(1);
			checkVk(VK12.vkCreateDescriptorSetLayout(dev, dsInfo, null, out), "vkCreateDescriptorSetLayout(mesh)");
			meshSetLayout = out.get(0);

			VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack)
				.stageFlags(taskShaderMode ? taskStage : meshStage)
				.offset(0)
				.size(8);
			VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(meshSetLayout))
				.pPushConstantRanges(pcRange);
			checkVk(VK12.vkCreatePipelineLayout(dev, plInfo, null, out), "vkCreatePipelineLayout(mesh)");
			meshPipelineLayout = out.get(0);
		}
	}

	private void ensureGraphicsPipelines(VulkanDevice vkDev, VulkanCommandEncoder encoder,
										 int colorFormat, int depthFormat) {
		EphemeraConfig cfg = EphemeraCommon.config();
		ShaderSourcePatcher.Options visualOpts = new ShaderSourcePatcher.Options(
			VisualsRuntime.smoothFog(),
			VisualsRuntime.gradientDithering(),
			cfg.visualDitherStrength,
			VisualsRuntime.atmosphericHorizon()
		);
		if (solidPipeline != 0L && cutoutPipeline != 0L
			&& colorFormat == compiledColorFormat && depthFormat == compiledDepthFormat
			&& visualOpts.equals(compiledVisualOptions)) {
			return;
		}
		VkDevice dev = vkDev.vkDevice();
		if (solidPipeline != 0L) {
			long old = solidPipeline;
			solidPipeline = 0L;
			encoder.queueForDestroy(() -> VK12.vkDestroyPipeline(dev, old, null));
		}
		if (cutoutPipeline != 0L) {
			long old = cutoutPipeline;
			cutoutPipeline = 0L;
			encoder.queueForDestroy(() -> VK12.vkDestroyPipeline(dev, old, null));
		}

		float cutoutThreshold = parseAlphaCutout(ChunkSectionLayer.CUTOUT.pipeline(), 0.1f);
		solidPipeline = buildMeshGraphicsPipeline(dev, ChunkSectionLayer.SOLID.pipeline(), 0.0f, visualOpts, colorFormat, depthFormat);
		cutoutPipeline = buildMeshGraphicsPipeline(dev, ChunkSectionLayer.CUTOUT.pipeline(), cutoutThreshold, visualOpts, colorFormat, depthFormat);
		compiledColorFormat = colorFormat;
		compiledDepthFormat = depthFormat;
		compiledVisualOptions = visualOpts;
	}

	private long buildMeshGraphicsPipeline(VkDevice dev, RenderPipeline template, float alphaCutout,
										   ShaderSourcePatcher.Options visualOpts, int colorFormat, int depthFormat) {
		String fragGlsl = VulkanMeshShaders.buildFragmentGlsl(alphaCutout, visualOpts);
		long fragModule = VulkanMeshShaders.compileShaderModule(dev,
			alphaCutout > 0.0f ? "ephemera_terrain_cutout.frag" : "ephemera_terrain_solid.frag",
			fragGlsl, Shaderc.shaderc_fragment_shader);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer entry = stack.UTF8("main");
			int stageCount = taskShaderMode ? 3 : 2;
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
			int s = 0;
			if (taskShaderMode) {
				stages.get(s++).sType$Default().stage(EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT).module(taskShaderModule).pName(entry);
			}
			stages.get(s++).sType$Default().stage(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT).module(meshShaderModule).pName(entry);
			stages.get(s).sType$Default().stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entry);

			VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
				.sType$Default()
				.polygonMode(VK12.VK_POLYGON_MODE_FILL)
				.cullMode(template.isCull() ? VK12.VK_CULL_MODE_BACK_BIT : VK12.VK_CULL_MODE_NONE)
				.frontFace(VK12.VK_FRONT_FACE_CLOCKWISE)
				.lineWidth(1.0f);

			DepthStencilState ds = template.getDepthStencilState();
			VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
				.sType$Default()
				.depthTestEnable(ds != null)
				.depthWriteEnable(ds != null && ds.writeDepth())
				.depthCompareOp(ds != null ? VulkanConst.toVk(ds.depthTest()) : VK12.VK_COMPARE_OP_LESS_OR_EQUAL);

			VkPipelineColorBlendAttachmentState.Buffer blendAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack)
				.colorWriteMask(VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT
					| VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT)
				.blendEnable(false);
			VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
				.sType$Default()
				.pAttachments(blendAtt);

			VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
				.sType$Default()
				.viewportCount(1)
				.scissorCount(1);

			VkPipelineMultisampleStateCreateInfo msaa = VkPipelineMultisampleStateCreateInfo.calloc(stack)
				.sType$Default()
				.rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT)
				.sampleShadingEnable(false);

			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
				.sType$Default()
				.pDynamicStates(stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR));

			VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
				.sType$Default()
				.pColorAttachmentFormats(stack.ints(colorFormat))
				.depthAttachmentFormat(depthFormat);

			VkGraphicsPipelineCreateInfo.Buffer gpInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
				.sType$Default()
				.pStages(stages)
				.pRasterizationState(raster)
				.pDepthStencilState(depthStencil)
				.pColorBlendState(colorBlend)
				.pViewportState(viewport)
				.pMultisampleState(msaa)
				.pDynamicState(dynamic)
				.layout(meshPipelineLayout)
				.pNext(rendering);

			LongBuffer out = stack.callocLong(1);
			checkVk(VK12.vkCreateGraphicsPipelines(dev, 0L, gpInfo, null, out), "vkCreateGraphicsPipelines(mesh)");
			return out.get(0);
		} finally {
			VK12.vkDestroyShaderModule(dev, fragModule, null);
		}
	}

	private void pushComputeDescriptors(VkCommandBuffer cmd,
										VulkanMeshArena.GpuBuf cullParams,
										VulkanMeshArena.GpuBuf visSections,
										VulkanMeshArena.GpuBuf sectionMeta,
										VulkanMeshArena.GpuBuf meshlets,
										VulkanMeshArena.GpuBuf visMeshlets,
										VulkanMeshArena.GpuBuf indirect) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorBufferInfo.Buffer bufs = VkDescriptorBufferInfo.calloc(6, stack);
			bufs.get(0).buffer(cullParams.buffer).offset(0L).range(VulkanMeshArena.CULL_PARAMS_BYTES);
			bufs.get(1).buffer(visSections.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(2).buffer(sectionMeta.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(3).buffer(meshlets.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(4).buffer(visMeshlets.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(5).buffer(indirect.buffer).offset(0L).range(VulkanMeshArena.INDIRECT_CMD_BYTES);

			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
			for (int i = 0; i < 6; i++) {
				VkWriteDescriptorSet w = writes.get(i).sType$Default()
					.dstBinding(i)
					.descriptorCount(1)
					.descriptorType(i == 0 ? VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
				MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PBUFFERINFO, bufs.address(i));
			}
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, computePipelineLayout, 0, writes);
		}
	}

	private void pushGraphicsDescriptors(VkCommandBuffer cmd,
										 GpuBufferSlice projSlice, GpuBufferSlice fogSlice, GpuBufferSlice globalsSlice,
										 VulkanMeshArena.GpuBuf meshFrame, VulkanMeshArena.GpuBuf cullParams,
										 VulkanGpuTextureView atlas, VulkanGpuSampler atlasSampler,
										 VulkanGpuTextureView lightmap, VulkanGpuSampler lightmapSampler) {
		int bufCount = taskShaderMode ? 9 : 8;
		int writeCount = taskShaderMode ? 11 : 10;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorBufferInfo.Buffer bufs = VkDescriptorBufferInfo.calloc(bufCount, stack);
			bufs.get(0).buffer(((VulkanGpuBuffer) projSlice.buffer()).vkBuffer()).offset(projSlice.offset()).range(projSlice.length());
			bufs.get(1).buffer(((VulkanGpuBuffer) fogSlice.buffer()).vkBuffer()).offset(fogSlice.offset()).range(fogSlice.length());
			bufs.get(2).buffer(((VulkanGpuBuffer) globalsSlice.buffer()).vkBuffer()).offset(globalsSlice.offset()).range(globalsSlice.length());
			bufs.get(3).buffer(meshFrame.buffer).offset(0L).range(VulkanMeshArena.MESH_FRAME_BYTES);
			bufs.get(4).buffer(arena.quadBuf.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(5).buffer(arena.meshletBuf.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(6).buffer(arena.sectionMetaBuf.buffer).offset(0L).range(VK12.VK_WHOLE_SIZE);
			bufs.get(7).buffer(taskShaderMode ? arena.visibleTasksBuf().buffer : arena.visibleMeshletsBuf().buffer)
				.offset(0L).range(VK12.VK_WHOLE_SIZE);
			if (taskShaderMode) {
				bufs.get(8).buffer(cullParams.buffer).offset(0L).range(VulkanMeshArena.CULL_PARAMS_BYTES);
			}

			VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(2, stack);
			imgs.get(0).sampler(atlasSampler.vkSampler()).imageView(atlas.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
			imgs.get(1).sampler(lightmapSampler.vkSampler()).imageView(lightmap.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);

			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
			for (int i = 0; i < 4; i++) {
				VkWriteDescriptorSet w = writes.get(i).sType$Default().dstBinding(i).descriptorCount(1)
					.descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
				MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PBUFFERINFO, bufs.address(i));
			}
			for (int i = 0; i < 2; i++) {
				VkWriteDescriptorSet w = writes.get(4 + i).sType$Default().dstBinding(4 + i).descriptorCount(1)
					.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
				MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PIMAGEINFO, imgs.address(i));
			}
			for (int i = 0; i < 4; i++) {
				VkWriteDescriptorSet w = writes.get(6 + i).sType$Default().dstBinding(6 + i).descriptorCount(1)
					.descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
				MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PBUFFERINFO, bufs.address(4 + i));
			}
			if (taskShaderMode) {
				VkWriteDescriptorSet w = writes.get(10).sType$Default().dstBinding(10).descriptorCount(1)
					.descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
				MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PBUFFERINFO, bufs.address(8));
			}
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, meshPipelineLayout, 0, writes);
		}
	}

	private static @Nullable VulkanEncoderBridge currentBridge() {
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		return ((CommandEncoderBackendAccessor) encoder).ephemera$backend() instanceof VulkanEncoderBridge b ? b : null;
	}

	private static float parseAlphaCutout(RenderPipeline pipeline, float fallback) {
		try {
			String val = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
			if (val != null) {
				return Float.parseFloat(val);
			}
		} catch (Throwable ignored) {}
		return fallback;
	}

	private static void checkVk(int res, String op) {
		if (res != VK12.VK_SUCCESS) {
			throw new IllegalStateException(op + " failed with VkResult=" + res);
		}
	}

	private void disable(Throwable t) {
		if (!failed) {
			failed = true;
			frameSolidTasks = 0;
			frameCutoutTasks = 0;
			frameActiveLayerMask = 0;
			VulkanModernCapabilities.setMeshEngineActive(false);
			LOGGER.error("Ephemera VK_EXT_mesh_shader terrain engine failed; falling back to regional multi-draw indirect", t);
		}
	}
}
