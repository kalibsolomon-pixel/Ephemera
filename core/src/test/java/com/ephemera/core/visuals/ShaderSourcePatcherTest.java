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

	/** Shape of the preprocessed vanilla {@code core/terrain.vsh} (imports abbreviated to the relevant blocks). */
	private static final String TERRAIN_VSH = """
		#version 330

		layout(std140) uniform ChunkSection {
		    mat4 ModelViewMat;
		    float ChunkVisibility;
		    ivec2 TextureSize;
		    ivec3 ChunkPosition;
		};

		in vec3 Position;
		in vec4 Color;

		out vec4 vertexColor;

		void main() {
		    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
		    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
		    vertexColor = Color;
		}
		""";

	/** Shape of the preprocessed vanilla {@code core/terrain.fsh}, fog include inlined. */
	private static final String TERRAIN_FSH = SYNTHETIC.replace(
		"""
		void main() {
		    fragColor = apply_fog(vec4(1.0), sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
		}
		""",
		"""
		void main() {
		    vec4 color = vec4(1.0);
		    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
		#ifdef ALPHA_CUTOUT
		    if (color.a < ALPHA_CUTOUT) {
		        discard;
		    }
		#endif
		    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
		}
		""");

	@Test
	void glSingleMdiVertexReadsBatchEntryOnlyForNonZeroBaseInstance() {
		String patched = ShaderSourcePatcher.patchGlSingleMdiVertex(TERRAIN_VSH, 4096);
		assertNotSame(TERRAIN_VSH, patched);
		String[] lines = patched.split("\n");
		assertEquals("#version 330", lines[0], "#version must stay on line 1");
		assertEquals("#extension GL_ARB_shader_draw_parameters : enable", lines[1]);
		assertTrue(patched.contains("uvec4 EphemeraBatchEntries[4096];"));
		assertTrue(patched.contains("flat out float ephemeraChunkVisibility;"));
		assertTrue(patched.indexOf("uniform EphemeraTerrainBatch") < patched.indexOf("void main()"));
		assertTrue(patched.contains("if (gl_BaseInstanceARB > 0) {"));
		assertTrue(patched.contains("EphemeraBatchEntries[gl_BaseInstanceARB - 1]"));
		assertTrue(patched.contains("ephemeraVis = uintBitsToFloat(ephemeraEntry.w);"));
		assertTrue(patched.contains("vec3 pos = Position + (ephemeraOrigin - CameraBlockPos) + CameraOffset;"));
		assertFalse(patched.contains("(ChunkPosition - CameraBlockPos)"));
		// Defaults are the vanilla uniforms, so base-instance-0 draws stay identical.
		assertTrue(patched.contains("ivec3 ephemeraOrigin = ChunkPosition;"));
		assertTrue(patched.contains("float ephemeraVis = ChunkVisibility;"));
		assertTrue(balanced(patched));
		assertEquals(1, count(patched, "#extension"));
		assertEquals(patched, ShaderSourcePatcher.patchGlSingleMdiVertex(patched, 4096), "idempotent");
		assertTrue(ShaderSourcePatcher.patchGlSingleMdiVertex(TERRAIN_VSH, 1024).contains("EphemeraBatchEntries[1024];"));
	}

	@Test
	void glSingleMdiVertexFailsSoftOnModifiedShader() {
		String moved = TERRAIN_VSH.replace(
			"vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;",
			"vec3 pos = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;");
		assertSame(moved, ShaderSourcePatcher.patchGlSingleMdiVertex(moved, 4096));
		String noVersion = TERRAIN_VSH.replace("#version 330\n", "");
		assertSame(noVersion, ShaderSourcePatcher.patchGlSingleMdiVertex(noVersion, 4096));
		String noMain = TERRAIN_VSH.replace("void main()", "void ephemeraMain()");
		assertSame(noMain, ShaderSourcePatcher.patchGlSingleMdiVertex(noMain, 4096));
		String plain = "#version 330\nvoid main() { gl_Position = vec4(0.0); }\n";
		assertSame(plain, ShaderSourcePatcher.patchGlSingleMdiVertex(plain, 4096));
		assertNull(ShaderSourcePatcher.patchGlSingleMdiVertex(null, 4096));
	}

	@Test
	void glSingleMdiFragmentUsesVaryingVisibility() {
		String patched = ShaderSourcePatcher.patchGlSingleMdiFragment(TERRAIN_FSH);
		assertNotSame(TERRAIN_FSH, patched);
		assertTrue(patched.startsWith("#version 330\n"));
		assertTrue(patched.contains("flat in float ephemeraChunkVisibility;"));
		assertTrue(patched.indexOf("flat in float ephemeraChunkVisibility;") < patched.indexOf("void main()"));
		assertTrue(patched.contains("color = mix(FogColor * vec4(1, 1, 1, color.a), color, ephemeraChunkVisibility);"));
		assertFalse(patched.contains(", ChunkVisibility);"));
		assertTrue(balanced(patched));
		assertEquals(patched, ShaderSourcePatcher.patchGlSingleMdiFragment(patched), "idempotent");

		String modified = TERRAIN_FSH.replace(", ChunkVisibility);", ", clamp(ChunkVisibility, 0.0, 1.0));");
		assertSame(modified, ShaderSourcePatcher.patchGlSingleMdiFragment(modified));
		assertNull(ShaderSourcePatcher.patchGlSingleMdiFragment(null));
	}

	@Test
	void glSingleMdiFragmentComposesWithFogAndDitherPatches() {
		String mdi = ShaderSourcePatcher.patchGlSingleMdiFragment(TERRAIN_FSH);
		Result r = ShaderSourcePatcher.patch(mdi, ALL);
		assertTrue(r.smoothFogApplied());
		assertTrue(r.applyFogRewritten());
		String both = r.source();
		assertTrue(both.contains("ephemeraChunkVisibility);"));
		assertTrue(both.contains("float ephemera_ign("));
		assertEquals(1, count(both, "flat in float ephemeraChunkVisibility;"));
		assertTrue(both.startsWith("#version 330\n"));
		assertTrue(balanced(both));
		assertEquals(both, ShaderSourcePatcher.patchGlSingleMdiFragment(both));
		assertEquals(both, ShaderSourcePatcher.patchFragment(both, ALL));
	}

	@Test
	void glSingleMdiDeclaresVaryingsBeforeWrappedMainHelpersAndCommentedMain() {
		String auroraWrapped = "#version 330\n"
			+ "// void main() wrapper comment\n"
			+ "uniform float ChunkVisibility;\n"
			+ "out vec4 fragColor;\n"
			+ "void aurora_orig_main() {\n"
			+ "    fragColor = mix(vec4(0.0), vec4(1.0), ChunkVisibility);\n"
			+ "}\n"
			+ "void main() {\n"
			+ "    aurora_orig_main();\n"
			+ "}\n";
		String patchedF = ShaderSourcePatcher.patchGlSingleMdiFragment(auroraWrapped);
		int declAt = patchedF.indexOf("flat in float ephemeraChunkVisibility;");
		int useAt = patchedF.indexOf(", ephemeraChunkVisibility);");
		assertTrue(declAt > 0 && useAt > declAt, "declaration must precede helper-function use");
		assertTrue(balanced(patchedF));

		String commentedV = "#version 330\n"
			+ "// void main() handles terrain lighting\n"
			+ "layout(std140) uniform ChunkSection { ivec3 ChunkPosition; float ChunkVisibility; };\n"
			+ "in vec3 Position;\n"
			+ "void main() {\n"
			+ "    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;\n"
			+ "}\n";
		String patchedV = ShaderSourcePatcher.patchGlSingleMdiVertex(commentedV, 1024);
		int uboAt = patchedV.indexOf("layout(std140) uniform EphemeraTerrainBatch");
		int commentAt = patchedV.indexOf("// void main() handles terrain lighting");
		assertTrue(uboAt > 0 && uboAt < commentAt, "UBO declaration must be placed cleanly after #version/#extension preamble");
		assertTrue(balanced(patchedV));
	}

	@Test
	void realVanillaTerrainShadersPatchForGlSingleMdi() throws IOException {
		Path dir = shaderDir();
		Assumptions.assumeTrue(dir != null, "MC 26.2 shader dump not present");
		String vsh = expandImports(dir, Files.readString(dir.resolve("core__terrain.vsh"), StandardCharsets.UTF_8));
		String patchedV = ShaderSourcePatcher.patchGlSingleMdiVertex(vsh, 4096);
		assertNotSame(vsh, patchedV, "vertex shape recognised");
		assertTrue(patchedV.startsWith("#version 330\n#extension GL_ARB_shader_draw_parameters : enable\n"));
		assertTrue(balanced(patchedV));
		assertEquals(patchedV, ShaderSourcePatcher.patchGlSingleMdiVertex(patchedV, 4096));

		String fsh = expandImports(dir, Files.readString(dir.resolve("core__terrain.fsh"), StandardCharsets.UTF_8));
		String patchedF = ShaderSourcePatcher.patchGlSingleMdiFragment(fsh);
		assertNotSame(fsh, patchedF, "fragment shape recognised");
		assertTrue(balanced(patchedF));
		Result r = ShaderSourcePatcher.patch(patchedF, ALL);
		assertTrue(r.smoothFogApplied() && r.applyFogRewritten());
		assertTrue(r.source().contains(", ephemeraChunkVisibility);"));
	}

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
