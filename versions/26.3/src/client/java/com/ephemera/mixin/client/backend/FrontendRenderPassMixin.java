package com.ephemera.mixin.client.backend;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.FrontendPipelineUniforms;
import com.ephemera.render.backend.VulkanRenderPassBridge;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates RenderPearl frontend overhead shared by both OpenGL and Vulkan on 26.3:
 * <ul>
 *   <li>Skips no-op {@code setPipeline}, {@code setUniform}, {@code setVertexBuffer}, and
 *       {@code setIndexBuffer} calls with zero {@link TextureViewAndSampler} / {@link GpuBufferSlice}
 *       allocations;</li>
 *   <li>Replaces the {@code HashMap<String, Object> uniforms} + {@code HashMap.forEach} +
 *       {@code Object2IntOpenHashMap.getOrDefault} path with a tiny L1-resident flat array and
 *       per-pipeline {@link FrontendPipelineUniforms} identity lookup (safe whenever
 *       {@code STRICT_VALIDATION} is false, as {@code validateDraw} returns immediately).</li>
 * </ul>
 */
@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassMixin {
	@Shadow @Final private RenderPassBackend backend;
	@Shadow @Final private DeviceLimits deviceLimits;
	@Shadow @Final private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;
	@Shadow private FrontendRenderPipeline boundPipeline;
	@Shadow private boolean constantsPushed;
	@Shadow @Final private GpuBufferSlice[] vertexBuffers;
	@Shadow protected GpuBuffer indexBuffer;
	@Shadow @Final protected HashMap<String, Object> uniforms;

	@Unique private boolean ephemera$cacheEnabled;
	@Unique private int ephemera$minUniformAlign;
	@Unique private IndexType ephemera$indexType;
	@Unique private int ephemera$seenExternalDrawSeq;
	@Unique private String[] ephemera$uNames;
	@Unique private Object[] ephemera$uVals;
	@Unique private int ephemera$uCount;

	@Unique
	private boolean ephemera$syncExternalDraw() {
		if (backend instanceof VulkanRenderPassBridge bridge) {
			int seq = bridge.ephemera$externalDrawSeq();
			if (seq != ephemera$seenExternalDrawSeq) {
				ephemera$seenExternalDrawSeq = seq;
				Arrays.fill(vertexBuffers, null);
				ephemera$indexType = null;
				return true;
			}
			return !bridge.ephemera$isPipelineValid();
		}
		return false;
	}

	@Unique
	private int ephemera$findUniform(String name) {
		String[] names = ephemera$uNames;
		int count = ephemera$uCount;
		for (int i = 0; i < count; i++) {
			if (names[i] == name) {
				return i;
			}
		}
		for (int i = 0; i < count; i++) {
			if (names[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	@Unique
	private void ephemera$putUniform(int existingIdx, String name, Object val) {
		if (existingIdx >= 0) {
			ephemera$uVals[existingIdx] = val;
		} else {
			int count = ephemera$uCount;
			if (count == ephemera$uNames.length) {
				int next = count << 1;
				ephemera$uNames = Arrays.copyOf(ephemera$uNames, next);
				ephemera$uVals = Arrays.copyOf(ephemera$uVals, next);
			}
			ephemera$uNames[count] = name;
			ephemera$uVals[count] = val;
			ephemera$uCount = count + 1;
		}
		FrontendRenderPipeline pipe = boundPipeline;
		if (pipe != null) {
			int slot = ((FrontendPipelineUniforms) (Object) pipe).ephemera$findSlot(name);
			if (slot != -1) {
				backend.setUniform(slot, val);
			}
		}
	}

	@Unique
	private void ephemera$removeUniform(int existingIdx, String name) {
		int last = --ephemera$uCount;
		if (existingIdx != last) {
			ephemera$uNames[existingIdx] = ephemera$uNames[last];
			ephemera$uVals[existingIdx] = ephemera$uVals[last];
		}
		ephemera$uNames[last] = null;
		ephemera$uVals[last] = null;
		FrontendRenderPipeline pipe = boundPipeline;
		if (pipe != null) {
			int slot = ((FrontendPipelineUniforms) (Object) pipe).ephemera$findSlot(name);
			if (slot != -1) {
				backend.setUniform(slot, null);
			}
		}
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$initCache(CallbackInfo ci) {
		if (FrontendGpuDevice.STRICT_VALIDATION || backend == null) {
			ephemera$cacheEnabled = false;
			return;
		}
		if (backend instanceof VulkanRenderPass) {
			ephemera$cacheEnabled = EphemeraCommon.config().backendVulkanDispatchCache;
		} else if ("com.mojang.renderpearl.backend.opengl.GlRenderPass".equals(backend.getClass().getName())) {
			ephemera$cacheEnabled = DispatchSettings.glDispatch();
		} else {
			ephemera$cacheEnabled = false;
		}
		if (ephemera$cacheEnabled) {
			ephemera$minUniformAlign = deviceLimits != null ? deviceLimits.minUniformOffsetAlignment() : 1;
			ephemera$uNames = new String[16];
			ephemera$uVals = new Object[16];
		}
	}

	@Inject(method = "setPipeline", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastSetPipeline(CompiledRenderPipeline pipeline, CallbackInfo ci) {
		if (!ephemera$cacheEnabled) {
			return;
		}
		boolean externalDirty = ephemera$syncExternalDraw();
		if (!externalDirty && boundPipeline != null && boundPipeline == pipeline) {
			ci.cancel();
			return;
		}
		if (!(pipeline instanceof FrontendRenderPipeline fe)) {
			return;
		}
		// Keep vanilla color attachment count/format validation.
		List<ColorTargetState> targets = fe.colorTargetStates();
		int nAttach = colorAttachments.size();
		if (targets.size() != nAttach) {
			return;
		}
		for (int i = 0; i < nAttach; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> att = colorAttachments.get(i);
			if (att != null) {
				ColorTargetState cts = targets.get(i);
				if (cts == null || cts.format() != att.textureView().texture().getFormat()) {
					return;
				}
			}
		}
		boundPipeline = fe;
		backend.setPipeline(fe.backendRenderPipeline());
		int uCount = ephemera$uCount;
		if (uCount > 0) {
			FrontendPipelineUniforms pu = (FrontendPipelineUniforms) (Object) fe;
			String[] pNames = pu.ephemera$uniformNames();
			int[] pSlots = pu.ephemera$uniformSlots();
			String[] uNames = ephemera$uNames;
			Object[] uVals = ephemera$uVals;
			for (int p = 0, pLen = pNames.length; p < pLen; p++) {
				String pName = pNames[p];
				int found = -1;
				for (int u = 0; u < uCount; u++) {
					if (uNames[u] == pName) {
						found = u;
						break;
					}
				}
				if (found < 0) {
					for (int u = 0; u < uCount; u++) {
						if (uNames[u].equals(pName)) {
							found = u;
							break;
						}
					}
				}
				if (found >= 0) {
					backend.setUniform(pSlots[p], uVals[found]);
				}
			}
		}
		constantsPushed = false;
		ci.cancel();
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$fastSetTexture(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
		if (!ephemera$cacheEnabled) {
			return;
		}
		if (view == null && sampler == null) {
			int idx = ephemera$findUniform(name);
			if (idx >= 0) {
				ephemera$removeUniform(idx, name);
			}
			ci.cancel();
			return;
		}
		if (view == null || sampler == null) {
			return; // Let vanilla throw IllegalArgumentException.
		}
		int idx = ephemera$findUniform(name);
		if (idx >= 0 && ephemera$uVals[idx] instanceof TextureViewAndSampler prev
				&& prev.view() == view && prev.sampler() == sampler) {
			ci.cancel();
			return;
		}
		ephemera$putUniform(idx, name, new TextureViewAndSampler(view, sampler));
		ci.cancel();
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$fastSetBuffer(String name, GpuBuffer buffer, CallbackInfo ci) {
		if (!ephemera$cacheEnabled || buffer == null) {
			return;
		}
		int idx = ephemera$findUniform(name);
		if (idx >= 0 && ephemera$uVals[idx] instanceof GpuBufferSlice prev
				&& DispatchSettings.sameSlice(prev, buffer, 0L, buffer.size())) {
			ci.cancel();
			return;
		}
		ephemera$putUniform(idx, name, buffer.slice());
		ci.cancel();
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",
		at = @At("HEAD"), cancellable = true)
	private void ephemera$fastSetSlice(String name, GpuBufferSlice slice, CallbackInfo ci) {
		if (!ephemera$cacheEnabled || slice == null) {
			return;
		}
		int idx = ephemera$findUniform(name);
		if (idx >= 0 && ephemera$uVals[idx] instanceof GpuBufferSlice prev
				&& DispatchSettings.sameSlice(prev, slice)) {
			ci.cancel();
			return;
		}
		int align = ephemera$minUniformAlign;
		if (align > 1 && slice.offset() % align > 0L) {
			return; // Let vanilla throw IllegalArgumentException.
		}
		ephemera$putUniform(idx, name, slice);
		ci.cancel();
	}

	@Inject(method = "setVertexBuffer", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipSameVertexBuffer(int slot, GpuBufferSlice slice, CallbackInfo ci) {
		if (!ephemera$cacheEnabled || slot < 0 || slot >= 16 || ephemera$syncExternalDraw()) {
			return;
		}
		GpuBufferSlice prev = vertexBuffers[slot];
		if (slice == null) {
			if (prev == null) {
				ci.cancel();
			}
			return;
		}
		if (prev != null && !slice.buffer().isClosed()
				&& prev.buffer() == slice.buffer() && prev.offset() == slice.offset()) {
			ci.cancel();
		}
	}

	@Inject(method = "setIndexBuffer", at = @At("HEAD"), cancellable = true)
	private void ephemera$skipSameIndexBuffer(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
		if (ephemera$cacheEnabled && !ephemera$syncExternalDraw() && indexBuffer == buffer && ephemera$indexType == type) {
			ci.cancel();
			return;
		}
		ephemera$indexType = type;
	}
}
