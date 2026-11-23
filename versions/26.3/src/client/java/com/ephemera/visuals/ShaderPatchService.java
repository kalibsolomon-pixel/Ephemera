package com.ephemera.visuals;

import com.ephemera.EphemeraClient;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.core.visuals.ShaderSourcePatcher;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Only patched Java text is cached (in a small bounded LRU); native {@code CachedIncludeSource}
 * structs are owned per compilation call and freed immediately once shaderc finishes compiling.
 */
public final class ShaderPatchService {
	private static final int MAX_TEXT_CACHE_ENTRIES = 32;
	private static final String UNCHANGED_SENTINEL = new String("");

	private static final Map<IncludeKey, String> PATCHED_TEXT_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<IncludeKey, String> eldest) {
			return size() > MAX_TEXT_CACHE_ENTRIES;
		}
	};
	private static volatile boolean failureLogged;

	private record IncludeKey(String rawSource, ShaderSourcePatcher.Options options, boolean heldWorldLight) {}

	private ShaderPatchService() {}

	public static String patchStageSource(String shaderName, ShaderType type, String source) {
		if (source == null || type != ShaderType.VERTEX || shaderName == null || !isTerrainShader(shaderName)) {
			return source;
		}
		try {
			VisualsRuntime.init();
			if (!VisualsRuntime.heldWorldLightTerrainShader()) {
				return source;
			}
			return ShaderSourcePatcher.patchTerrainHeldLightVertex(source);
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				EphemeraClient.LOGGER.warn("Terrain held-light vertex patching failed for {}; using vanilla source: {}", shaderName, e.toString());
				MixinHealth.recordFailed("ShaderSourceMixin", e.toString());
			}
			return source;
		}
	}

	public static ShaderSource wrapForStage(String shaderName, ShaderType type, String stageSource, ShaderSource delegate) {
		if (delegate == null || shaderName == null) {
			return delegate;
		}
		if (type == ShaderType.VERTEX && isTerrainShader(shaderName)
			&& stageSource != null && stageSource.contains(ShaderSourcePatcher.HELD_LIGHT_POS_UNIFORM)) {
			return new PatchedIncludeShaderSource(delegate, null, true, shaderName);
		}
		if (type != ShaderType.FRAGMENT || !isCoreShader(shaderName)) {
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
		return new PatchedIncludeShaderSource(delegate, options, false, shaderName);
	}

	/** Frees any native {@code CachedIncludeSource} entries created during a single compilation. */
	public static void releaseStageResources(ShaderSource shaderSource) {
		if (shaderSource instanceof PatchedIncludeShaderSource wrapper) {
			wrapper.releasePatchedIncludes();
		}
	}

	/** Drops cached patched Java strings on resource reload so old pack fog sources are not retained. */
	public static void clearTextCache() {
		synchronized (PATCHED_TEXT_CACHE) {
			PATCHED_TEXT_CACHE.clear();
		}
	}

	private static boolean isCoreShader(String shaderName) {
		int colon = shaderName.indexOf(':');
		String path = colon >= 0 ? shaderName.substring(colon + 1) : shaderName;
		return path.startsWith("core/");
	}

	private static boolean isTerrainShader(String shaderName) {
		int colon = shaderName.indexOf(':');
		String namespace = colon >= 0 ? shaderName.substring(0, colon) : "minecraft";
		String path = colon >= 0 ? shaderName.substring(colon + 1) : shaderName;
		return "minecraft".equals(namespace) && ("core/terrain".equals(path) || "core/terrain.vsh".equals(path));
	}

	private static String resolvePatchedText(String rawText, ShaderSourcePatcher.Options options, boolean heldLightGlobals, String shaderName) {
		IncludeKey key = new IncludeKey(rawText, options, heldLightGlobals);
		synchronized (PATCHED_TEXT_CACHE) {
			String cached = PATCHED_TEXT_CACHE.get(key);
			if (cached != null) {
				return cached == UNCHANGED_SENTINEL ? null : cached;
			}
		}
		String patched;
		if (heldLightGlobals) {
			String out = ShaderSourcePatcher.patchGlobalsForHeldLight(rawText);
			patched = out != rawText ? out : null;
		} else {
			ShaderSourcePatcher.Result result = ShaderSourcePatcher.patch(rawText, options);
			if (result.changed()) {
				EphemeraClient.LOGGER.debug("Patched fog include for {} (smoothFog={}, applyFog={})",
					shaderName, result.smoothFogApplied(), result.applyFogRewritten());
				patched = result.source();
			} else {
				patched = null;
			}
		}
		String value = patched != null ? patched : UNCHANGED_SENTINEL;
		synchronized (PATCHED_TEXT_CACHE) {
			PATCHED_TEXT_CACHE.put(key, value);
		}
		return patched;
	}

	private static final class PatchedIncludeShaderSource implements ShaderSource {
		private final ShaderSource delegate;
		private final ShaderSourcePatcher.Options options;
		private final boolean heldLightGlobals;
		private final String shaderName;
		private final List<CachedIncludeSource> ownedIncludes = new ArrayList<>(1);

		PatchedIncludeShaderSource(ShaderSource delegate, ShaderSourcePatcher.Options options, boolean heldLightGlobals, String shaderName) {
			this.delegate = delegate;
			this.options = options;
			this.heldLightGlobals = heldLightGlobals;
			this.shaderName = shaderName;
		}

		@Override
		public String getShader(Identifier id, ShaderType type) {
			return delegate.getShader(id, type);
		}

		@Override
		public CachedIncludeSource getInclude(Identifier id) {
			CachedIncludeSource raw = delegate.getInclude(id);
			if (id == null || !"minecraft".equals(id.getNamespace())) {
				return raw;
			}
			boolean match = heldLightGlobals ? "globals.glsl".equals(id.getPath()) : "fog.glsl".equals(id.getPath());
			if (!match) {
				return raw;
			}
			if (raw == null || raw.includeResultPtr() == 0L) {
				return raw;
			}
			try {
				String rawText = ShadercIncludeResult.create(raw.includeResultPtr()).contentString();
				if (rawText == null || rawText.isEmpty()) {
					return raw;
				}
				String patchedText = resolvePatchedText(rawText, options, heldLightGlobals, shaderName);
				if (patchedText == null) {
					return raw;
				}
				CachedIncludeSource nativeInclude = CachedIncludeSource.create(id, patchedText);
				synchronized (ownedIncludes) {
					ownedIncludes.add(nativeInclude);
				}
				return nativeInclude;
			} catch (RuntimeException e) {
				if (!failureLogged) {
					failureLogged = true;
					EphemeraClient.LOGGER.warn("Shader patching failed for {}; using vanilla include: {}", shaderName, e.toString());
					MixinHealth.recordFailed("ShaderSourceMixin", e.toString());
				}
				return raw;
			}
		}

		void releasePatchedIncludes() {
			synchronized (ownedIncludes) {
				for (CachedIncludeSource inc : ownedIncludes) {
					try {
						inc.close();
					} catch (Throwable ignored) {
					}
				}
				ownedIncludes.clear();
			}
		}

		@Override
		public void close() {
			releasePatchedIncludes();
			delegate.close();
		}
	}
}
