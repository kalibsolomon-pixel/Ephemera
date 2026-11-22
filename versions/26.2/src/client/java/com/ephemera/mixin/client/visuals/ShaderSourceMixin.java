package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.ShaderPatchService;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single choke point for GLSL handed to the GPU backend: OpenGL and Vulkan both pull their
 * (already import-expanded) shader text through {@code CompilationCache.getShaderSource}.
 */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class ShaderSourceMixin {
	@Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
	private void ephemera$patchShaderSource(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
		String original = cir.getReturnValue();
		String patched = ShaderPatchService.patch(id, type, original);
		if (patched != original) {
			cir.setReturnValue(patched);
		}
	}
}
