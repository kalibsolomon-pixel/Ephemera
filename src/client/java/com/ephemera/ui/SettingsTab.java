package com.ephemera.ui;

import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.FrameMetrics;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.api.EphemeraVisibilityProvider;
import com.ephemera.visuals.DynamicLightTracker;
import com.ephemera.visuals.VisualsDiagnostics;
import com.ephemera.visuals.VisualsRuntime;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The six tabs of the settings screen and the rows each one shows. */
enum SettingsTab {
	PERFORMANCE("performance"),
	TERRAIN("terrain"),
	CULLING("culling"),
	ENGINE("engine"),
	VISUALS("visuals"),
	DIAGNOSTICS("diagnostics");

	private static final List<Integer> ATLAS_SIZES = List.of(256, 512, 1024, 2048, 4096);
	private static final List<Integer> PARTICLE_CAPS = List.of(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536);
	private static final List<Integer> ANISOTROPY = List.of(0, 2, 4, 8, 16);

	final String id;

	SettingsTab(String id) {
		this.id = id;
	}

	String titleKey() {
		return "ephemera.tab." + id;
	}

	String shortKey() {
		return "ephemera.tab." + id + ".short";
	}

	List<SettingRow> rows(EphemeraConfig c) {
		List<SettingRow> r = new ArrayList<>();
		switch (this) {
			case PERFORMANCE -> performance(r, c);
			case TERRAIN -> terrain(r, c);
			case CULLING -> culling(r, c);
			case ENGINE -> engine(r, c);
			case VISUALS -> visuals(r, c);
			case DIAGNOSTICS -> diagnostics(r, c);
		}
		return r;
	}

	private static void performance(List<SettingRow> r, EphemeraConfig c) {
		r.add(new SettingRow.Header("ephemera.section.backend"));
		toggles(r, c, "backendGlDispatchCache", "backendGlNoErrorContext", "backendVulkanDispatchCache", "backendVulkanBufferViewCache");
		r.add(new SettingRow.Header("ephemera.section.gui"));
		toggles(r, c, "guiLayerIndexing", "guiFastLtrText", "guiMergeTextDecorations", "guiFontAtlasResizing");
		r.add(new SettingRow.Choice<>(c, "guiFontAtlasSize", ATLAS_SIZES, v -> v + " px"));
		toggles(r, c, "guiAnimatedItemBaking", "guiGlintRateLimit");
		r.add(new SettingRow.Slider(c, "guiGlintFps", 10, 240, 5, v -> v.intValue() + " fps"));
		toggles(r, c, "guiSkipTextTranslucencySort", "guiHudFrameCache");
		r.add(new SettingRow.Header("ephemera.section.particles"));
		toggles(r, c, "particleCulling", "particleOcclusionCulling", "particleLightmapCache", "particleAsyncTick");
		r.add(new SettingRow.Choice<>(c, "maxParticlesPerSheet", PARTICLE_CAPS, v -> String.format(Locale.ROOT, "%,d", v)));
	}

	private static void terrain(List<SettingRow> r, EphemeraConfig c) {
		r.add(new SettingRow.Header("ephemera.section.terrainEngine"));
		toggles(r, c, "terrainEngineEnabled", "terrainMultiDraw", "terrainDirectionalFaceCulling", "terrainBitmaskMeshing",
			"terrainCompactVertexFormat", "terrainFogOcclusion", "terrainAsyncGraphUpdates", "terrainAnimateOnlyVisibleTextures",
			"terrainFastTranslucentSort", "terrainPrefixSumBiomeBlend");
		r.add(new SettingRow.Header("ephemera.section.chunkBuilding"));
		r.add(new SettingRow.Slider(c, "chunkBuilderThreads", 0, 32, 1, v -> v.intValue() == 0 ? "Auto" : v.intValue() + " threads"));
		r.add(new SettingRow.Slider(c, "maxSectionUploadsPerFrameMs", 1, 32, 1, v -> v.intValue() + " ms"));
	}

	private static void culling(List<SettingRow> r, EphemeraConfig c) {
		r.add(new SettingRow.Header("ephemera.section.occlusion"));
		toggles(r, c, "entityOcclusionCulling", "blockEntityOcclusionCulling", "entityRendererDirectCache", "blockEntityRendererDirectCache");
		r.add(new SettingRow.Header("ephemera.section.blocks"));
		toggles(r, c, "blockStateCulling");
		r.add(new SettingRow.Choice<>(c, "leavesCullingMode", List.of(EphemeraConfig.LeavesCullingMode.values()),
			m -> m.name().charAt(0) + m.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ')));
		r.add(new SettingRow.Slider(c, "leavesCullingDepth", 1, 4, 1, v -> v.intValue() + " blocks"));
		r.add(new SettingRow.Header("ephemera.section.faces"));
		toggles(r, c, "signTextBackfaceCulling", "itemFrameCulling", "paintingBackfaceCulling", "weatherCulling", "cloudFaceCulling");
	}

	private static void engine(List<SettingRow> r, EphemeraConfig c) {
		r.add(new SettingRow.Header("ephemera.section.memory"));
		toggles(r, c, "memoryCompactBlockStates", "memoryFreeSpriteMipmaps", "memoryDeduplicateShapes");
		r.add(new SettingRow.Header("ephemera.section.loading"));
		toggles(r, c, "loadingNonBlockingReload", "loadingSkipTitleFade", "loadingSkipLevelLoadDelay", "loadingFastIpPing");
		r.add(new SettingRow.Header("ephemera.section.engine"));
		toggles(r, c, "engineFastMath", "engineFastLighting", "engineFastChunkLookup", "engineLightmapCaching",
			"engineSoundListenerThrottle", "engineDebugHudCache", "engineIdleShortCircuits", "networkFlushConsolidation");
		r.add(new SettingRow.Header("ephemera.section.pacing"));
		toggles(r, c, "unfocusedFpsLimitEnabled");
		r.add(new SettingRow.Slider(c, "unfocusedFpsLimit", 5, 240, 5, v -> v.intValue() + " fps"));
		r.add(new SettingRow.Header("ephemera.section.compat"));
		toggles(r, c, "modStandDownEnabled");
	}

	private static void visuals(List<SettingRow> r, EphemeraConfig c) {
		r.add(new SettingRow.Header("ephemera.section.atmosphere"));
		toggles(r, c, "visualSmoothFog", "visualGradientDithering");
		r.add(new SettingRow.Slider(c, "visualDitherStrength", 0.0, 2.0, 0.05, v -> String.format(Locale.ROOT, "x%.2f", v)));
		toggles(r, c, "visualAtmosphericHorizon", "visualUnderwaterClarity");
		r.add(new SettingRow.Action("reloadShaders", () -> Minecraft.getInstance().reloadResourcePacks()));
		r.add(new SettingRow.Header("ephemera.section.lighting"));
		toggles(r, c, "visualDynamicLights");
		r.add(new SettingRow.Slider(c, "visualDynamicLightsUpdateIntervalMs", 0, 100, 4, v -> v.intValue() + " ms"));
		toggles(r, c, "visualEnhancedAmbientOcclusion");
		r.add(new SettingRow.Slider(c, "visualAoStrength", 0.5, 1.5, 0.02, v -> String.format(Locale.ROOT, "%.2f", v)));
		r.add(new SettingRow.Header("ephemera.section.terrainVisuals"));
		toggles(r, c, "visualSmoothChunkFade");
		r.add(new SettingRow.Slider(c, "visualChunkFadeDurationSeconds", 0.0, 2.0, 0.05, v -> String.format(Locale.ROOT, "%.2f s", v)));
		toggles(r, c, "visualCrispMipmaps");
		r.add(new SettingRow.Choice<>(c, "visualAnisotropicLevel", ANISOTROPY, v -> v == 0 ? "Off" : v + "x"));
		r.add(new SettingRow.Header("ephemera.section.blockLook"));
		toggles(r, c, "visualSmoothBiomeBlending", "visualBetterLeavesShading", "visualBetterGrass", "visualCleanGlassBorders");
	}

	private static void diagnostics(List<SettingRow> r, EphemeraConfig c) {
		r.add(new SettingRow.Header("ephemera.section.live"));
		r.add(new SettingRow.Stat("ephemera.stat.backend", VisualsDiagnostics::backendName, () -> UiTheme.ACCENT));
		r.add(new SettingRow.Stat("ephemera.stat.fps", () -> {
			FrameMetrics.Snapshot s = VisualsDiagnostics.snapshot();
			return s.samples() == 0 ? "..." : String.format(Locale.ROOT, "%.0f avg / %d now", s.avgFps(), Minecraft.getInstance().getFps());
		}, () -> UiTheme.TEXT));
		r.add(new SettingRow.Stat("ephemera.stat.median", () -> ms(VisualsDiagnostics.snapshot().medianMs()), () -> UiTheme.TEXT));
		r.add(new SettingRow.Stat("ephemera.stat.onePercentLow", () -> fps(VisualsDiagnostics.snapshot().onePercentLowFps()),
			() -> lowColor(VisualsDiagnostics.snapshot().onePercentLowFps())));
		r.add(new SettingRow.Stat("ephemera.stat.pointOnePercentLow", () -> fps(VisualsDiagnostics.snapshot().pointOnePercentLowFps()),
			() -> lowColor(VisualsDiagnostics.snapshot().pointOnePercentLowFps())));
		r.add(new SettingRow.Stat("ephemera.stat.p99", () -> ms(VisualsDiagnostics.snapshot().p99Ms()), () -> UiTheme.TEXT));
		r.add(new SettingRow.Header("ephemera.section.culling"));
		r.add(new SettingRow.Stat("ephemera.stat.visibleSections", () -> count(provider() == null ? -1 : provider().visibleSectionCount()), () -> UiTheme.TEXT));
		r.add(new SettingRow.Stat("ephemera.stat.occludedEntities", () -> count(provider() == null ? -1 : provider().occludedEntityCount()), () -> UiTheme.TEXT));
		r.add(new SettingRow.Stat("ephemera.stat.occludedBlockEntities", () -> count(provider() == null ? -1 : provider().occludedBlockEntityCount()), () -> UiTheme.TEXT));
		r.add(new SettingRow.Header("ephemera.section.health"));
		r.add(new SettingRow.Stat("ephemera.stat.mixins", MixinHealth::summary, () -> MixinHealth.hasAnyFailure() ? UiTheme.BAD : UiTheme.GOOD));
		r.add(new SettingRow.Stat("ephemera.stat.failedMixins", () -> names(MixinHealth.failed().keySet()),
			() -> MixinHealth.hasAnyFailure() ? UiTheme.BAD : UiTheme.TEXT_DIM));
		r.add(new SettingRow.Stat("ephemera.stat.shaderPack", () -> VisualsRuntime.externalShadersActive() ? "Active (visuals stand down)" : "None",
			() -> VisualsRuntime.externalShadersActive() ? UiTheme.WARN : UiTheme.TEXT_DIM));
		r.add(new SettingRow.Stat("ephemera.stat.dynamicLights", () -> VisualsRuntime.dynamicLights() ? DynamicLightTracker.emitterCount() + " emitters" : "Off",
			() -> UiTheme.TEXT));
		r.add(new SettingRow.Header("ephemera.section.overlay"));
		toggles(r, c, "diagnosticsHudOverlay");
	}

	private static void toggles(List<SettingRow> rows, EphemeraConfig c, String... fields) {
		for (String f : fields) {
			rows.add(new SettingRow.Toggle(c, f));
		}
	}

	private static EphemeraVisibilityProvider provider() {
		return VisualsDiagnostics.visibility();
	}

	private static String ms(double v) {
		return v <= 0.0 ? "..." : String.format(Locale.ROOT, "%.2f ms", v);
	}

	private static String fps(double v) {
		return v <= 0.0 ? "..." : String.format(Locale.ROOT, "%.0f fps", v);
	}

	private static String count(int v) {
		return v < 0 ? "n/a" : String.format(Locale.ROOT, "%,d", v);
	}

	private static String names(java.util.Collection<String> values) {
		return values.isEmpty() ? "none" : String.join(", ", values);
	}

	private static int lowColor(double fps) {
		if (fps <= 0.0) {
			return UiTheme.TEXT_DIM;
		}
		return fps >= 60.0 ? UiTheme.GOOD : fps >= 30.0 ? UiTheme.WARN : UiTheme.BAD;
	}
}
