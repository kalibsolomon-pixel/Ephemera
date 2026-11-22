package com.ephemera.visuals;

import com.ephemera.EphemeraClient;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.core.visuals.ShaderSourcePatcher;
import com.ephemera.render.terrain.gl.GlSingleMdiTerrain;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

/**
 * Applies the smooth-fog / dithering / horizon patches to core fragment shader sources as they
 * are handed to the GPU backend (OpenGL and Vulkan both go through the same source provider),
 * plus the OpenGL single multi-draw batch lookup to {@code core/terrain} when it is usable.
 * Config changes take effect the next time shaders compile (resource reload, F3+T).
 */
public final class ShaderPatchService {
	private static boolean failureLogged;

	private ShaderPatchService() {}

	public static String patch(Identifier id, ShaderType type, String source) {
		if (source == null || !id.getPath().startsWith("core/")) {
			return source;
		}
		try {
			if ("minecraft".equals(id.getNamespace()) && "core/terrain".equals(id.getPath())) {
				// The fragment half reads a varying only the patched vertex stage writes, so it is
				// applied only when this pipeline's own vertex stage (compiled first) was patched.
				boolean singleMdi = GlSingleMdiTerrain.compilingTerrainProgram() && GlSingleMdiTerrain.shouldPatchShaders();
				if (type == ShaderType.VERTEX) {
					String patched = singleMdi ? ShaderSourcePatcher.patchGlSingleMdiVertex(source, GlSingleMdiTerrain.maxEntries()) : source;
					GlSingleMdiTerrain.recordVertexPatched(patched != source);
					return patched;
				}
				if (type == ShaderType.FRAGMENT) {
					String patched = singleMdi && GlSingleMdiTerrain.shouldPatchFragment()
						? ShaderSourcePatcher.patchGlSingleMdiFragment(source) : source;
					GlSingleMdiTerrain.recordFragmentPatched(patched != source);
					source = patched;
				}
			}
			if (type != ShaderType.FRAGMENT) {
				return source;
			}
			VisualsRuntime.init();
			EphemeraConfig cfg = VisualsRuntime.cfg();
			ShaderSourcePatcher.Options options = new ShaderSourcePatcher.Options(
				VisualsRuntime.smoothFog(),
				VisualsRuntime.gradientDithering(),
				cfg.visualDitherStrength,
				VisualsRuntime.atmosphericHorizon()
			);
			if (!options.any()) {
				return source;
			}
			ShaderSourcePatcher.Result result = ShaderSourcePatcher.patch(source, options);
			if (result.changed()) {
				EphemeraClient.LOGGER.debug("Patched shader {} (smoothFog={}, applyFog={})", id, result.smoothFogApplied(), result.applyFogRewritten());
			}
			return result.source();
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				EphemeraClient.LOGGER.warn("Shader patching failed for {}; using vanilla source: {}", id, e.toString());
				MixinHealth.recordFailed("ShaderSourceMixin", e.toString());
			}
			return source;
		}
	}
}
