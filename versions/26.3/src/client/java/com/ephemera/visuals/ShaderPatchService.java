package com.ephemera.visuals;

import com.ephemera.EphemeraClient;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.core.visuals.ShaderSourcePatcher;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/**
 * 26.3 shader patching via RenderPearl's unified {@code GlslCompiler} include path.
 *
 * <p>In 26.3 both OpenGL and Vulkan compile GLSL through {@code GlslCompiler} (shaderc), which
 * resolves {@code #include <minecraft:fog.glsl>} via {@link ShaderSource#getInclude}. Intercepting
 * {@code minecraft:fog.glsl} exclusively during {@link ShaderType#FRAGMENT} compilation of
 * {@code core/*} shaders applies smooth fog, gradient dithering, and atmospheric horizon across
 * both backends without ever introducing fragment-only builtins ({@code gl_FragCoord}) into
 * vertex shaders.
 */
public final class ShaderPatchService {
	private static final ConcurrentHashMap<IncludeKey, ShaderSource.CachedIncludeSource> PATCHED_FOG_CACHE = new ConcurrentHashMap<>();
	private static volatile boolean failureLogged;

	private record IncludeKey(String rawSource, ShaderSourcePatcher.Options options) {}

	private ShaderPatchService() {}

	public static ShaderSource wrapForStage(String shaderName, ShaderType type, ShaderSource delegate) {
		if (delegate == null || type != ShaderType.FRAGMENT || shaderName == null || !isCoreShader(shaderName)) {
			return delegate;
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
			return delegate;
		}
		return new FogPatchingShaderSource(delegate, options, shaderName);
	}

	private static boolean isCoreShader(String shaderName) {
		int colon = shaderName.indexOf(':');
		String path = colon >= 0 ? shaderName.substring(colon + 1) : shaderName;
		return path.startsWith("core/");
	}

	private static ShaderSource.CachedIncludeSource patchFogInclude(Identifier id, ShaderSource.CachedIncludeSource raw,
			ShaderSourcePatcher.Options options, String shaderName) {
		if (raw == null || raw.includeResultPtr() == 0L) {
			return raw;
		}
		try {
			String rawText = ShadercIncludeResult.create(raw.includeResultPtr()).contentString();
			if (rawText == null || rawText.isEmpty()) {
				return raw;
			}
			IncludeKey key = new IncludeKey(rawText, options);
			ShaderSource.CachedIncludeSource cached = PATCHED_FOG_CACHE.get(key);
			if (cached != null) {
				return cached;
			}
			ShaderSourcePatcher.Result result = ShaderSourcePatcher.patch(rawText, options);
			if (!result.changed()) {
				return raw;
			}
			EphemeraClient.LOGGER.debug("Patched fog include for {} (smoothFog={}, applyFog={})",
				shaderName, result.smoothFogApplied(), result.applyFogRewritten());
			return PATCHED_FOG_CACHE.computeIfAbsent(key, k -> ShaderSource.CachedIncludeSource.create(id, result.source()));
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				EphemeraClient.LOGGER.warn("Shader patching failed for {}; using vanilla include: {}", shaderName, e.toString());
				MixinHealth.recordFailed("ShaderSourceMixin", e.toString());
			}
			return raw;
		}
	}

	private static final class FogPatchingShaderSource implements ShaderSource {
		private final ShaderSource delegate;
		private final ShaderSourcePatcher.Options options;
		private final String shaderName;

		FogPatchingShaderSource(ShaderSource delegate, ShaderSourcePatcher.Options options, String shaderName) {
			this.delegate = delegate;
			this.options = options;
			this.shaderName = shaderName;
		}

		@Override
		public String getShader(Identifier id, ShaderType type) {
			return delegate.getShader(id, type);
		}

		@Override
		public CachedIncludeSource getInclude(Identifier id) {
			CachedIncludeSource raw = delegate.getInclude(id);
			if (id != null && "minecraft".equals(id.getNamespace()) && "fog.glsl".equals(id.getPath())) {
				return patchFogInclude(id, raw, options, shaderName);
			}
			return raw;
		}

		@Override
		public void close() {
			delegate.close();
		}
	}
}
