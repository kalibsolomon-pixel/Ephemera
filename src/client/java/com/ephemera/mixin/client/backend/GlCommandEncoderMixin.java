package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.DispatchSettings;
import com.ephemera.render.backend.GlTextureMipState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
abstract class GlCommandEncoderMixin {
	@Unique private static long ephemera$mipGeneration;
	@Unique private static boolean ephemera$previouslyEnabled;
	@Unique private Object ephemera$drawBuffersPass;
	@Unique private int ephemera$drawFramebuffer;
	@Unique private Map<String, Uniform> ephemera$lastUniforms;
	@Unique private Set<Map.Entry<String, Uniform>> ephemera$textureUniforms;

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
		ephemera$observeDispatch(DispatchSettings.glDispatch());
	}

	@Unique
	private void ephemera$observeDispatch(boolean enabled) {
		if (!enabled) {
			if (ephemera$previouslyEnabled) {
				ephemera$mipGeneration++;
			}
			ephemera$drawBuffersPass = null;
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
		target = "Lorg/lwjgl/opengl/GL33C;glDrawBuffers([I)V"))
	private void ephemera$setDrawBuffers(int[] buffers, Operation<Void> original,
			@Share("pass") LocalRef<Object> currentPass, @Share("dispatch") LocalBooleanRef dispatch) {
		Object pass = currentPass.get();
		int framebuffer = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
		if (!dispatch.get() || pass != ephemera$drawBuffersPass || framebuffer != ephemera$drawFramebuffer) {
			original.call(buffers);
			if (dispatch.get()) {
				ephemera$drawBuffersPass = pass;
				ephemera$drawFramebuffer = framebuffer;
			}
		}
	}

	@Inject(method = {"submitRenderPass", "clearDepthTexture"}, at = @At("HEAD"))
	private void ephemera$invalidateDrawBuffers(CallbackInfo ci) {
		ephemera$drawBuffersPass = null;
	}
}
