package com.ephemera.core.visuals;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idempotent, fail-soft patcher for Minecraft's preprocessed fragment shader sources
 * (after {@code #moj_import} expansion, so {@code fog.glsl} is inlined).
 *
 * <p>Output stays plain GLSL 330 with no extensions, so it compiles through both the
 * OpenGL driver and Blaze3D's GLSL-to-SPIR-V path. Each feature only applies when the
 * expected vanilla function shape is found; anything else is left byte-for-byte untouched.
 * The exception is the OpenGL-only single multi-draw terrain patch
 * ({@link #patchGlSingleMdiVertex}), which enables {@code GL_ARB_shader_draw_parameters}.
 */
public final class ShaderSourcePatcher {
	public static final String MARKER = "// ephemera:visuals-patched";
	private static final String VANILLA_APPLY_FOG = "ephemera_vanilla_apply_fog";
	/** Uniform block holding {@code (originX, originY, originZ, floatBits(visibility))} per single multi-draw entry. */
	public static final String GL_BATCH_BLOCK = "EphemeraTerrainBatch";
	private static final String GL_BATCH_VISIBILITY = "ephemeraChunkVisibility";
	private static final String MAIN_DECL = "void main()";
	private static final String VANILLA_TERRAIN_POS = "vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;";
	private static final String VANILLA_FADE_ARG = ", ChunkVisibility);";

	private static final Pattern LINEAR_FOG_DECL = Pattern.compile("\\bfloat\\s+linear_fog_value\\s*\\(");
	private static final Pattern LINEAR_FOG_RETURN = Pattern.compile(
		"return\\s*\\(\\s*vertexDistance\\s*-\\s*fogStart\\s*\\)\\s*/\\s*\\(\\s*fogEnd\\s*-\\s*fogStart\\s*\\)\\s*;");
	private static final Pattern APPLY_FOG_DECL = Pattern.compile(
		"\\bvec4\\s+apply_fog\\s*\\(\\s*vec4\\s+\\w+\\s*(?:,\\s*float\\s+\\w+\\s*){6},\\s*vec4\\s+\\w+\\s*\\)");

	private ShaderSourcePatcher() {}

	public record Options(boolean smoothFog, boolean gradientDither, float ditherStrength, boolean atmosphericHorizon) {
		public boolean ditherActive() {
			return gradientDither && ditherStrength > 0.0f;
		}

		public boolean any() {
			return smoothFog || ditherActive() || atmosphericHorizon;
		}
	}

	public record Result(String source, boolean smoothFogApplied, boolean applyFogRewritten) {
		public boolean changed() {
			return smoothFogApplied || applyFogRewritten;
		}
	}

	public static boolean isPatched(String source) {
		return source != null && source.contains(MARKER);
	}

	/** Cheap pre-check so callers can skip sources that do not define the fog helpers at all. */
	public static boolean definesFog(String source) {
		return source != null && LINEAR_FOG_DECL.matcher(source).find();
	}

	public static String patchFragment(String source, Options options) {
		return patch(source, options).source();
	}

	public static Result patch(String source, Options options) {
		if (source == null || source.isEmpty() || options == null || !options.any() || isPatched(source)
			|| !definesFog(source)) {
			return new Result(source, false, false);
		}

		String out = source;
		boolean smooth = false;
		if (options.smoothFog()) {
			Matcher m = LINEAR_FOG_RETURN.matcher(out);
			if (m.find()) {
				String replacement = MARKER + " smooth-fog\n"
					+ "    float ephemeraFogT = (vertexDistance - fogStart) / (fogEnd - fogStart);\n"
					+ "    return ephemeraFogT * ephemeraFogT * (3.0 - 2.0 * ephemeraFogT);";
				out = out.substring(0, m.start()) + replacement + out.substring(m.end());
				smooth = true;
			}
		}

		boolean rewritten = false;
		if (options.ditherActive() || options.atmosphericHorizon()) {
			Matcher m = APPLY_FOG_DECL.matcher(out);
			if (m.find()) {
				int bodyStart = out.indexOf('{', m.end());
				int bodyEnd = bodyStart < 0 ? -1 : matchingBrace(out, bodyStart);
				if (bodyEnd > 0) {
					String renamed = out.substring(m.start(), m.end()).replace("apply_fog", VANILLA_APPLY_FOG);
					String original = out.substring(0, m.start()) + renamed + out.substring(m.end(), bodyEnd + 1);
					out = original + "\n\n" + MARKER + " apply-fog\n" + buildApplyFog(options) + out.substring(bodyEnd + 1);
					rewritten = true;
				}
			}
		}

		if (!smooth && !rewritten) {
			return new Result(source, false, false);
		}
		return new Result(out, smooth, rewritten);
	}

	private static String buildApplyFog(Options o) {
		StringBuilder sb = new StringBuilder(900);
		if (o.ditherActive()) {
			sb.append("float ephemera_ign(vec2 p) {\n")
				.append("    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));\n")
				.append("}\n\n");
		}
		sb.append("vec4 apply_fog(vec4 inColor, float sph, float cyl, float envStart, float envEnd, ")
			.append("float rdStart, float rdEnd, vec4 fogColor) {\n")
			.append("    float envFog = linear_fog_value(sph, envStart, envEnd);\n")
			.append("    float rdFog = linear_fog_value(cyl, rdStart, rdEnd);\n")
			.append("    float fogValue = max(envFog, rdFog);\n")
			.append("    vec3 fogRgb = fogColor.rgb;\n");
		if (o.atmosphericHorizon()) {
			// Warm the distance haze only where render-distance fog dominates (never underwater / in lava).
			sb.append("    fogRgb *= 1.0 + vec3(0.040, 0.016, -0.026) * (rdFog * (1.0 - envFog));\n");
		}
		sb.append("    vec3 rgb = mix(inColor.rgb, fogRgb, fogValue * fogColor.a);\n");
		if (o.ditherActive()) {
			sb.append("    if (fogValue > 0.0) {\n")
				.append("        rgb += (ephemera_ign(gl_FragCoord.xy) - 0.5) * ")
				.append(String.format(Locale.ROOT, "%.6f", o.ditherStrength() / 255.0f))
				.append(";\n")
				.append("    }\n");
		}
		sb.append("    return vec4(rgb, inColor.a);\n}");
		return sb.toString();
	}

	/**
	 * OpenGL single multi-draw terrain: lets {@code core/terrain.vsh} take its section origin
	 * and fade visibility from {@code EphemeraBatchEntries[gl_BaseInstanceARB - 1]} when a draw
	 * has a non-zero base instance. Base instance 0 (vanilla, wireframe, translucent and
	 * fallback draws) keeps {@code ChunkPosition} and {@code ChunkVisibility} exactly. Returns
	 * {@code source} unchanged unless the vanilla shape is found.
	 */
	public static String patchGlSingleMdiVertex(String source, int maxEntries) {
		if (source == null || maxEntries <= 0 || source.contains(GL_BATCH_BLOCK) || !source.startsWith("#version")
			|| !source.contains("ChunkPosition") || !hasMainOutsideComments(source)
			|| !source.contains(VANILLA_TERRAIN_POS)) {
			return source;
		}
		int versionEnd = source.indexOf('\n');
		int insertAt = afterDirectivePreamble(source);
		if (versionEnd < 0 || insertAt <= versionEnd) {
			return source;
		}
		String declarations = "\nlayout(std140) uniform " + GL_BATCH_BLOCK + " {\n"
			+ "    uvec4 EphemeraBatchEntries[" + maxEntries + "];\n"
			+ "};\n"
			+ "flat out float " + GL_BATCH_VISIBILITY + ";\n\n";
		String out = source.substring(0, versionEnd + 1)
			+ "#extension GL_ARB_shader_draw_parameters : enable\n"
			+ source.substring(versionEnd + 1, insertAt)
			+ declarations
			+ source.substring(insertAt);
		return out.replace(VANILLA_TERRAIN_POS,
			"ivec3 ephemeraOrigin = ChunkPosition;\n"
				+ "    float ephemeraVis = ChunkVisibility;\n"
				+ "    if (gl_BaseInstanceARB > 0) {\n"
				+ "        uvec4 ephemeraEntry = EphemeraBatchEntries[gl_BaseInstanceARB - 1];\n"
				+ "        ephemeraOrigin = ivec3(ephemeraEntry.xyz);\n"
				+ "        ephemeraVis = uintBitsToFloat(ephemeraEntry.w);\n"
				+ "    }\n"
				+ "    " + GL_BATCH_VISIBILITY + " = ephemeraVis;\n"
				+ "    vec3 pos = Position + (ephemeraOrigin - CameraBlockPos) + CameraOffset;");
	}

	/**
	 * Fragment half of {@link #patchGlSingleMdiVertex}: the fade mix reads the visibility the
	 * vertex stage resolved instead of {@code ChunkVisibility}. Inserts the {@code flat in}
	 * declaration right after the {@code #version}/{@code #extension} preamble so mods that wrap
	 * {@code main()} into earlier helper functions (e.g. Aurora gradient dithering) or resource
	 * packs with {@code void main()} inside comments always see it declared in scope.
	 */
	public static String patchGlSingleMdiFragment(String source) {
		if (source == null || source.contains(GL_BATCH_VISIBILITY) || !source.startsWith("#version")
			|| !hasMainOutsideComments(source) || !source.contains(VANILLA_FADE_ARG)) {
			return source;
		}
		int insertAt = afterDirectivePreamble(source);
		if (insertAt < 0) {
			return source;
		}
		String out = source.substring(0, insertAt)
			+ "\nflat in float " + GL_BATCH_VISIBILITY + ";\n\n"
			+ source.substring(insertAt);
		return out.replace(VANILLA_FADE_ARG, ", " + GL_BATCH_VISIBILITY + ");");
	}

	/**
	 * Returns the character index immediately after the leading {@code #version}, {@code #extension},
	 * blank lines, and comments — the earliest legal GLSL global-declaration insertion point.
	 */
	private static int afterDirectivePreamble(String s) {
		int n = s.length();
		int i = 0;
		int lastDirectiveEnd = -1;
		while (i < n) {
			// Skip leading horizontal whitespace and newlines
			while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\r' || s.charAt(i) == '\n')) {
				i++;
			}
			if (i >= n) {
				break;
			}
			// Skip line comments
			if (s.charAt(i) == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
				int eol = s.indexOf('\n', i);
				if (eol < 0) {
					break;
				}
				i = eol + 1;
				continue;
			}
			// Skip block comments
			if (s.charAt(i) == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				int end = s.indexOf("*/", i + 2);
				if (end < 0) {
					return -1;
				}
				i = end + 2;
				continue;
			}
			if (s.startsWith("#version", i) || s.startsWith("#extension", i)) {
				int eol = s.indexOf('\n', i);
				if (eol < 0) {
					return -1;
				}
				lastDirectiveEnd = eol + 1;
				i = eol + 1;
				continue;
			}
			break;
		}
		return lastDirectiveEnd;
	}

	/** True when a real {@code void main()} declaration exists outside GLSL comments. */
	private static boolean hasMainOutsideComments(String s) {
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (c == '/' && i + 1 < n) {
				char d = s.charAt(i + 1);
				if (d == '/') {
					int eol = s.indexOf('\n', i);
					if (eol < 0) {
						return false;
					}
					i = eol;
					continue;
				}
				if (d == '*') {
					int end = s.indexOf("*/", i + 2);
					if (end < 0) {
						return false;
					}
					i = end + 1;
					continue;
				}
			}
			if (c == 'v' && s.startsWith(MAIN_DECL, i)) {
				return true;
			}
		}
		return false;
	}

	/** Index of the brace closing the one at {@code open}, skipping comments; -1 if unbalanced. */
	static int matchingBrace(String s, int open) {
		int depth = 0;
		int n = s.length();
		for (int i = open; i < n; i++) {
			char c = s.charAt(i);
			if (c == '/' && i + 1 < n) {
				char d = s.charAt(i + 1);
				if (d == '/') {
					int eol = s.indexOf('\n', i);
					if (eol < 0) {
						return -1;
					}
					i = eol;
					continue;
				}
				if (d == '*') {
					int end = s.indexOf("*/", i + 2);
					if (end < 0) {
						return -1;
					}
					i = end + 1;
					continue;
				}
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}
}
