package com.ephemera.core.visuals;

import com.ephemera.core.visuals.ShaderSourcePatcher.Options;
import com.ephemera.core.visuals.ShaderSourcePatcher.Result;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ShaderSourcePatcherTest {
	private static final Options ALL = new Options(true, true, 1.0f, true);
	private static final Options FOG_ONLY = new Options(true, false, 1.0f, false);
	private static final Options NONE = new Options(false, false, 1.0f, false);

	/** Mirrors the shape of a preprocessed vanilla fragment shader (imports already inlined). */
	private static final String SYNTHETIC = """
		#version 330

		layout(std140) uniform Fog {
		    vec4 FogColor;
		    float FogEnvironmentalStart;
		    float FogEnvironmentalEnd;
		    float FogRenderDistanceStart;
		    float FogRenderDistanceEnd;
		    float FogSkyEnd;
		    float FogCloudsEnd;
		};

		float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
		    if (vertexDistance <= fogStart) {
		        return 0.0;
		    } else if (vertexDistance >= fogEnd) {
		        return 1.0;
		    }

		    return (vertexDistance - fogStart) / (fogEnd - fogStart);
		}

		vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
		    float fogValue = max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
		    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
		}

		in float sphericalVertexDistance;
		in float cylindricalVertexDistance;
		out vec4 fragColor;

		void main() {
		    fragColor = apply_fog(vec4(1.0), sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
		}
		""";

	@Test
	void smoothFogReplacesLinearRampWithHermiteCurve() {
		Result r = ShaderSourcePatcher.patch(SYNTHETIC, FOG_ONLY);
		assertTrue(r.smoothFogApplied());
		assertFalse(r.applyFogRewritten());
		assertTrue(r.source().contains("ephemeraFogT * ephemeraFogT * (3.0 - 2.0 * ephemeraFogT)"));
		assertFalse(r.source().contains("return (vertexDistance - fogStart) / (fogEnd - fogStart);"));
		// endpoint guards stay intact so the curve spans exactly [fogStart, fogEnd]
		assertTrue(r.source().contains("if (vertexDistance <= fogStart)"));
		assertTrue(r.source().contains("else if (vertexDistance >= fogEnd)"));
		assertTrue(ShaderSourcePatcher.isPatched(r.source()));
	}

	@Test
	void patchingIsIdempotent() {
		String once = ShaderSourcePatcher.patchFragment(SYNTHETIC, ALL);
		String twice = ShaderSourcePatcher.patchFragment(once, ALL);
		assertEquals(once, twice);
		assertEquals(1, count(once, "float ephemera_ign("));
		assertEquals(1, count(once, "\nvec4 apply_fog("));
	}

	@Test
	void noOpWhenNothingEnabled() {
		assertSame(SYNTHETIC, ShaderSourcePatcher.patchFragment(SYNTHETIC, NONE));
		// dither requested but strength zero and nothing else on
		assertSame(SYNTHETIC, ShaderSourcePatcher.patchFragment(SYNTHETIC, new Options(false, true, 0.0f, false)));
	}

	@Test
	void sourcesWithoutFogAreUntouched() {
		String plain = "#version 330\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n";
		assertSame(plain, ShaderSourcePatcher.patchFragment(plain, ALL));
		assertNull(ShaderSourcePatcher.patchFragment(null, ALL));
		assertEquals("", ShaderSourcePatcher.patchFragment("", ALL));
		assertFalse(ShaderSourcePatcher.definesFog(plain));
		assertTrue(ShaderSourcePatcher.definesFog(SYNTHETIC));
	}

	@Test
	void unrecognisedFogShapeFailsSoft() {
		String modified = SYNTHETIC.replace(
			"return (vertexDistance - fogStart) / (fogEnd - fogStart);",
			"return clamp((vertexDistance - fogStart) / (fogEnd - fogStart), 0.0, 1.0);");
		Result r = ShaderSourcePatcher.patch(modified, FOG_ONLY);
		assertFalse(r.smoothFogApplied());
		assertSame(modified, r.source());

		String renamedApply = SYNTHETIC.replace("vec4 apply_fog(", "vec4 apply_fog2(");
		Result r2 = ShaderSourcePatcher.patch(renamedApply, new Options(false, true, 1.0f, false));
		assertFalse(r2.changed());
		assertSame(renamedApply, r2.source());
	}

	@Test
	void ditherUsesInterleavedGradientNoiseScaledByStrength() {
		String patched = ShaderSourcePatcher.patchFragment(SYNTHETIC, new Options(false, true, 2.0f, false));
		assertTrue(patched.contains("fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))))"));
		assertTrue(patched.contains("gl_FragCoord.xy"));
		assertTrue(patched.contains(String.format(java.util.Locale.ROOT, "%.6f", 2.0f / 255.0f)));
		assertTrue(patched.contains("if (fogValue > 0.0)"));
		assertFalse(patched.contains("0.040, 0.016"), "horizon tint must stay off");
		assertFalse(patched.contains("ephemeraFogT"), "smooth fog must stay off");
	}

	@Test
	void horizonTintOnlyWeightsRenderDistanceFog() {
		String patched = ShaderSourcePatcher.patchFragment(SYNTHETIC, new Options(false, false, 1.0f, true));
		assertTrue(patched.contains("(rdFog * (1.0 - envFog))"));
		assertFalse(patched.contains("ephemera_ign"), "dither must stay off");
	}

	@Test
	void vanillaApplyFogIsKeptUnderANewNameAndCallersStillResolve() {
		String patched = ShaderSourcePatcher.patchFragment(SYNTHETIC, ALL);
		assertTrue(patched.contains("vec4 ephemera_vanilla_apply_fog("));
		int vanillaAt = patched.indexOf("vec4 ephemera_vanilla_apply_fog(");
		int replacementAt = patched.indexOf("vec4 apply_fog(");
		int mainAt = patched.indexOf("void main()");
		assertTrue(vanillaAt < replacementAt && replacementAt < mainAt, "declaration order must precede use");
		assertTrue(balanced(patched));
	}

	@Test
	void matchingBraceSkipsCommentsAndNesting() {
		String s = "{ a { b } // } \n /* } */ c }";
		assertEquals(s.length() - 1, ShaderSourcePatcher.matchingBrace(s, 0));
		assertEquals(-1, ShaderSourcePatcher.matchingBrace("{ { }", 0));
	}

	@Test
	void realVanillaShadersPatchCleanly() throws IOException {
		Path dir = shaderDir();
		Assumptions.assumeTrue(dir != null, "MC 26.2 shader dump not present");
		for (String name : new String[] {
			"core__sky.fsh", "core__terrain.fsh", "core__rendertype_clouds.fsh", "core__entity.fsh",
			"core__particle.fsh", "core__rendertype_lines.fsh"
		}) {
			String expanded = expandImports(dir, Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
			assertTrue(ShaderSourcePatcher.definesFog(expanded), name + " should inline fog.glsl");
			Result r = ShaderSourcePatcher.patch(expanded, ALL);
			assertTrue(r.smoothFogApplied(), name + " smooth fog");
			assertTrue(r.applyFogRewritten(), name + " apply_fog rewrite");
			assertTrue(balanced(r.source()), name + " braces balanced");
			assertTrue(r.source().startsWith("#version 330"), name + " keeps #version first");
			assertEquals(1, count(r.source(), "\nvec4 apply_fog("), name);
			assertTrue(r.source().contains("void main()"), name + " main preserved");
			assertEquals(r.source(), ShaderSourcePatcher.patchFragment(r.source(), ALL), name + " idempotent");
		}
	}

	@Test
	void realFogIncludeAlonePatchesLinearFogValue() throws IOException {
		Path dir = shaderDir();
		Assumptions.assumeTrue(dir != null, "MC 26.2 shader dump not present");
		String fog = Files.readString(dir.resolve("include__fog.glsl"), StandardCharsets.UTF_8);
		Result r = ShaderSourcePatcher.patch(fog, ALL);
		assertTrue(r.smoothFogApplied());
		assertTrue(r.applyFogRewritten());
		// untouched helpers other shaders import
		assertTrue(r.source().contains("float fog_spherical_distance(vec3 pos)"));
		assertTrue(r.source().contains("float total_fog_value("));
	}

	private static Path shaderDir() {
		for (String candidate : new String[] {"build/mc262-specs/shaders", "../build/mc262-specs/shaders"}) {
			Path p = Path.of(candidate);
			if (Files.isDirectory(p) && Files.exists(p.resolve("include__fog.glsl"))) {
				return p;
			}
		}
		return null;
	}

	/** Minimal stand-in for GlslPreprocessor: inline {@code #moj_import <minecraft:x.glsl>} once, drop nested #version. */
	private static String expandImports(Path dir, String source) throws IOException {
		Pattern imp = Pattern.compile("#moj_import\\s*<minecraft:([\\w./]+)>");
		StringBuilder out = new StringBuilder();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (String line : source.split("\n", -1)) {
			Matcher m = imp.matcher(line);
			if (m.find()) {
				String file = m.group(1);
				if (seen.add(file)) {
					String body = Files.readString(dir.resolve("include__" + file), StandardCharsets.UTF_8);
					out.append(body.replaceFirst("(?m)^#version.*\\R", "")).append('\n');
				}
			} else {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	private static boolean balanced(String s) {
		int depth = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '{') depth++;
			if (c == '}') depth--;
			if (depth < 0) return false;
		}
		return depth == 0;
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			n++;
		}
		return n;
	}
}
