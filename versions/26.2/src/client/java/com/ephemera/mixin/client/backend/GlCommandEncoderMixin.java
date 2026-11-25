package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlStateCache;
import com.ephemera.render.backend.GlTextureMipState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.lwjgl.opengl.GL30C;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.Uniform;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
abstract class GlCommandEncoderMixin {
	@Shadow @Final private long[] fences;
	@Shadow private long currentSubmitIndex;
	@Unique private static final long EPHEMERA$ZERO_POLL_COALESCE_NS = 50_000L;
	@Unique private final long[] ephemera$zeroPollChecked = {-1L, -1L};
	@Unique private final long[] ephemera$zeroPollNs = {0L, 0L};
	@Unique private static long ephemera$mipGeneration;
	@Unique private static boolean ephemera$previouslyEnabled;
	@Unique private Object ephemera$drawBuffersPass;
	@Unique private int ephemera$drawFramebuffer;
	@Unique private Map<String, Uniform> ephemera$lastUniforms;
	@Unique private Set<Map.Entry<String, Uniform>> ephemera$textureUniforms;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$resetDispatchCache(CallbackInfo ci) {
		GlStateCache.invalidateAll();
	}

	@Inject(method = "trySetup", at = @At("HEAD"))
	private void ephemera$checkDispatch(@Coerce Object pass, Collection<String> dynamicUniforms,
			CallbackInfoReturnable<Boolean> ci, @Share("dispatch") LocalBooleanRef dispatch,
			@Share("pass") LocalRef<Object> currentPass) {
		boolean enabled = DispatchSettings.glDispatch();
		dispatch.set(enabled);
		currentPass.set(pass);
		ephemera$observeDispatch(enabled);
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void ephemera$checkDispatchAtSubmit(CallbackInfo ci) {
		ephemera$zeroPollChecked[0] = -1L;
		ephemera$zeroPollChecked[1] = -1L;
		ephemera$drawBuffersPass = null;
		// Reset per-frame so any raw GL calls made by third-party mods between frames never linger.
		GlStateCache.invalidateAll();
		ephemera$observeDispatch(DispatchSettings.glDispatch());
	}

	/**
	 * Also reset per pass: mods that set raw GL scissor/polygon/sampler state between passes
	 * (UI libraries, wireframe toggles) must not have vanilla's next identical call skipped.
	 * Redundant binds happen within a pass, so this keeps nearly all of the savings.
	 */
	@Inject(method = "createRenderPass", at = @At("HEAD"))
	private void ephemera$invalidateCacheAtPass(CallbackInfoReturnable<?> cir) {
		GlStateCache.invalidateAll();
	}

	@Inject(method = "awaitSubmit(JJ)Z", at = @At("HEAD"), cancellable = true)
	private void ephemera$coalesceFencePolls(long submitIndex, long timeoutNs, CallbackInfoReturnable<Boolean> cir) {
		if (timeoutNs != 0L || !DispatchSettings.glDispatch()) {
			return;
		}
		if (currentSubmitIndex > submitIndex + 2L) {
			cir.setReturnValue(true);
			return;
		}
		if (submitIndex >= currentSubmitIndex) {
			cir.setReturnValue(false);
			return;
		}

		int slot = (int) (submitIndex % 2L);
		long fence = fences[slot];
		if (fence == 0L) {
			cir.setReturnValue(true);
			return;
		}
		long now = System.nanoTime();
		if (ephemera$zeroPollChecked[slot] == currentSubmitIndex
				&& now - ephemera$zeroPollNs[slot] < EPHEMERA$ZERO_POLL_COALESCE_NS) {
			cir.setReturnValue(false);
			return;
		}

		int status = GlStateManager._glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
		if (status == GL_TIMEOUT_EXPIRED) {
			ephemera$zeroPollChecked[slot] = currentSubmitIndex;
			ephemera$zeroPollNs[slot] = now;
			cir.setReturnValue(false);
		} else if (status != GL_WAIT_FAILED) {
			GL33C.glDeleteSync(fence);
			fences[slot] = 0L;
			cir.setReturnValue(true);
		}
	}

	@Unique
	private void ephemera$observeDispatch(boolean enabled) {
		if (!enabled) {
			if (ephemera$previouslyEnabled) {
				ephemera$mipGeneration++;
			}
			ephemera$drawBuffersPass = null;
			GlStateCache.invalidateAll();
		}
		ephemera$previouslyEnabled = enabled;
	}

	@WrapOperation(method = "trySetup", at = @At(value = "INVOKE", ordinal = 1,
		target = "Ljava/util/Map;entrySet()Ljava/util/Set;"))
	private Set<Map.Entry<String, Uniform>> ephemera$skipCleanUniformBuffers(Map<String, Uniform> uniforms,
			Operation<Set<Map.Entry<String, Uniform>>> original, @Local(index = 5) boolean programChanged,
			@Share("pass") LocalRef<Object> currentPass, @Share("dispatch") LocalBooleanRef dispatch) {
		if (!dispatch.get() || SharedConstants.IS_RUNNING_IN_IDE || programChanged
				|| !(currentPass.get() instanceof DispatchSettings.GlUniformState state) || state.ephemera$hasDirtyUniforms()) {
			return original.call(uniforms);
		}
		if (ephemera$lastUniforms != uniforms) {
			// Clean UBO entries do no work. Sampler and texel-buffer entries replay texture state every draw.
			Set<Map.Entry<String, Uniform>> textures = new LinkedHashSet<>();
			for (Map.Entry<String, Uniform> entry : original.call(uniforms)) {
				if (!(entry.getValue() instanceof Uniform.Ubo)) {
					textures.add(entry);
				}
			}
			ephemera$textureUniforms = textures.isEmpty() ? Set.of() : textures;
			ephemera$lastUniforms = uniforms;
		}
		return ephemera$textureUniforms;
	}

	@WrapOperation(method = "trySetup", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"), require = 2)
	private void ephemera$setMipParameter(int target, int parameter, int level, Operation<Void> original,
			@Local(index = 24) GlTexture texture, @Share("dispatch") LocalBooleanRef dispatch) {
		if (dispatch.get() && texture instanceof GlTextureMipState state) {
			long generation = ephemera$mipGeneration;
			if (!state.ephemera$hasMipParameter(parameter, level, generation)) {
				original.call(target, parameter, level);
				state.ephemera$recordMipParameter(parameter, level, generation);
			}
		} else {
			original.call(target, parameter, level);
		}
	}

	@WrapOperation(method = "trySetup", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glBindSampler(II)V"))
	private void ephemera$bindSampler(int unit, int sampler, Operation<Void> original,
			@Share("dispatch") LocalBooleanRef dispatch) {
		if (dispatch.get()) {
			GlStateCache.bindSampler(unit, sampler);
		} else {
			original.call(unit, sampler);
		}
	}

	@WrapOperation(method = "trySetup", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"))
	private void ephemera$bindSetupUniformBuffer(int target, int binding, int handle, long offset, long length,
			Operation<Void> original, @Share("dispatch") LocalBooleanRef dispatch) {
		ephemera$bindUniformBufferRange(target, binding, handle, offset, length, original, dispatch.get());
	}

	@WrapOperation(method = "lambda$executeDrawMultiple$0", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"))
	private static void ephemera$bindBatchUniformBuffer(int target, int binding, int handle, long offset, long length,
			Operation<Void> original) {
		ephemera$bindUniformBufferRange(target, binding, handle, offset, length, original, DispatchSettings.glDispatch());
	}

	@Unique
	private static void ephemera$bindUniformBufferRange(int target, int binding, int handle, long offset, long length,
			Operation<Void> original, boolean enabled) {
		if (target == GL_UNIFORM_BUFFER) {
			if (enabled) {
				GlStateCache.bindUniformBufferRange(binding, handle, offset, length);
				return;
			}
			GlStateCache.invalidateUniformBinding(binding);
		}
		original.call(target, binding, handle, offset, length);
	}

	@WrapOperation(method = "executeDrawIndirect", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindBuffer(II)V"), require = 2)
	private void ephemera$bindDrawIndirectBuffer(int target, int handle, Operation<Void> original) {
		if (target == GL_DRAW_INDIRECT_BUFFER && DispatchSettings.glDispatch()) {
			GlStateCache.bindDrawIndirectBuffer(handle);
		} else {
			if (target == GL_DRAW_INDIRECT_BUFFER) {
				GlStateCache.invalidateDrawIndirect();
			}
			original.call(target, handle);
		}
	}

	// The HUD alternates pipelines per draw and vanilla re-binds the element array on every
	// indexed draw with a raw uncached glBindBuffer. The binding is VAO-scoped state and all VAO
	// binds are tracked, so skip when the same element buffer is already bound on this VAO.
	@WrapOperation(method = {"executeDraws", "drawFromBuffers"}, at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindBuffer(II)V"))
	private void ephemera$skipRedundantElementArrayBind(int target, int handle, Operation<Void> original) {
		if (target == GL30C.GL_ELEMENT_ARRAY_BUFFER && DispatchSettings.glDispatch()
			&& GlStateCache.isElementArrayBufferBound(handle)) {
			return;
		}
		original.call(target, handle);
	}

	@Inject(method = "trySetup", at = @At(value = "INVOKE",
		target = "Ljava/util/Set;clear()V", shift = At.Shift.AFTER), cancellable = true)
	private void ephemera$setDrawBuffers(@Coerce Object pass, Collection<String> dynamicUniforms,
			CallbackInfoReturnable<Boolean> cir, @Share("dispatch") LocalBooleanRef dispatch) {
		if (!dispatch.get() || !(pass instanceof GlStateCache.RenderPassState state)) {
			return;
		}
		int[] buffers = GlStateCache.drawBuffers(state.ephemera$colorAttachmentCount());
		if (buffers == null) {
			return;
		}
		int framebuffer = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
		if (pass != ephemera$drawBuffersPass || framebuffer != ephemera$drawFramebuffer) {
			GL33C.glDrawBuffers(buffers);
			ephemera$drawBuffersPass = pass;
			ephemera$drawFramebuffer = framebuffer;
		}
		state.ephemera$applyScissor();
		cir.setReturnValue(true);
	}

	@Inject(method = {"submitRenderPass", "clearDepthTexture"}, at = @At("HEAD"))
	private void ephemera$invalidateDrawBuffers(CallbackInfo ci) {
		ephemera$drawBuffersPass = null;
	}
}
