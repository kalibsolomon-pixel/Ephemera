package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlStateCache;
import com.ephemera.render.backend.GlTextureMipState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
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

import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;

/**
 * 26.3 OpenGL command-encoder state cache and fence-poll coalescing.
 * Hooks 26.3's refactored {@code setupDraw(GlRenderPass)} and {@code executeDrawIndirect}
 * methods to elide redundant UBO range binds, sampler binds, texture mip-level parameters,
 * draw-indirect buffer binds, and zero-timeout fence polls.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
abstract class GlCommandEncoderMixin {
	@Shadow @Final private long[] fences;
	@Shadow private long currentSubmitIndex;
	@Unique private static final long EPHEMERA$ZERO_POLL_COALESCE_NS = 50_000L;
	@Unique private final long[] ephemera$zeroPollChecked = {-1L, -1L};
	@Unique private final long[] ephemera$zeroPollNs = {0L, 0L};
	@Unique private static long ephemera$mipGeneration;
	@Unique private static boolean ephemera$previouslyEnabled;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$resetDispatchCache(CallbackInfo ci) {
		GlStateCache.invalidateAll();
	}

	@Inject(method = "setupDraw", at = @At("HEAD"))
	private void ephemera$checkDispatch(@Coerce Object pass, CallbackInfo ci,
			@Share("dispatch") LocalBooleanRef dispatch) {
		boolean enabled = DispatchSettings.glDispatch();
		dispatch.set(enabled);
		ephemera$observeDispatch(enabled);
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void ephemera$checkDispatchAtSubmit(CallbackInfo ci) {
		ephemera$zeroPollChecked[0] = -1L;
		ephemera$zeroPollChecked[1] = -1L;
		GlStateCache.invalidateAll();
		ephemera$observeDispatch(DispatchSettings.glDispatch());
	}

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
			GlStateCache.invalidateAll();
		}
		ephemera$previouslyEnabled = enabled;
	}

	@WrapOperation(method = "setupDraw", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"))
	private void ephemera$bindSetupUniformBuffer(int target, int binding, int handle, long offset, long length,
			Operation<Void> original, @Share("dispatch") LocalBooleanRef dispatch) {
		if (target == GL_UNIFORM_BUFFER) {
			if (dispatch.get()) {
				GlStateCache.bindUniformBufferRange(binding, handle, offset, length);
				return;
			}
			GlStateCache.invalidateUniformBinding(binding);
		}
		original.call(target, binding, handle, offset, length);
	}

	@WrapOperation(method = "setupDraw", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL33C;glBindSampler(II)V"))
	private void ephemera$bindSampler(int unit, int sampler, Operation<Void> original,
			@Share("dispatch") LocalBooleanRef dispatch) {
		if (dispatch.get()) {
			GlStateCache.bindSampler(unit, sampler);
		} else {
			original.call(unit, sampler);
		}
	}

	@WrapOperation(method = "setupDraw", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_texParameter(III)V"), require = 2)
	private void ephemera$setMipParameter(int target, int parameter, int level, Operation<Void> original,
			@Local GlTexture texture, @Share("dispatch") LocalBooleanRef dispatch) {
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

	@WrapOperation(method = "executeDrawIndirect", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glBindBuffer(II)V"), require = 1)
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
}
