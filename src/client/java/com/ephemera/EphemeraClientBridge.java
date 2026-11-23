package com.ephemera;

import com.ephemera.api.EphemeraOptionDescriptor;
import com.ephemera.api.EphemeraSettingsBridge;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.platform.RenderPlatform;
import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.ui.EphemeraSettingsScreen;
import com.ephemera.visuals.VisualsRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ObjectShare;
import net.minecraft.client.gui.screens.Screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Registers zero-compile-coupling {@link ObjectShare} bridges ({@code ephemera:fast_write},
 * {@code ephemera:frame_pacer}, and {@code ephemera:settings}) and implements
 * {@link EphemeraSettingsBridge} so companion mods such as Aurora Client can invoke Ephemera's
 * native fast-write paths directly, coordinate frame pacing without double-sleeping, and embed
 * Ephemera's performance controls inside their own UI.
 */
final class EphemeraClientBridge implements EphemeraSettingsBridge {
	static final String SHARE_FAST_WRITE = "ephemera:fast_write";
	static final String SHARE_FRAME_PACER = "ephemera:frame_pacer";
	static final String SHARE_SETTINGS = "ephemera:settings";

	private static final EphemeraConfig DEFAULTS = new EphemeraConfig();

	private final String version;
	private final Supplier<String> backendSupplier;

	private EphemeraClientBridge(String version, Supplier<String> backendSupplier) {
		this.version = version;
		this.backendSupplier = backendSupplier;
	}

	static EphemeraClientBridge register(String version, Supplier<String> backendSupplier) {
		EphemeraClientBridge bridge = new EphemeraClientBridge(version, backendSupplier);
		ObjectShare share = FabricLoader.getInstance().getObjectShare();

		Map<String, MethodHandle> fastWrite = RenderPlatform.fastWriteHandles();
		if (!fastWrite.isEmpty()) {
			share.put(SHARE_FAST_WRITE, fastWrite);
		}

		Map<String, Object> framePacer = buildFramePacerBridge();
		if (!framePacer.isEmpty()) {
			share.put(SHARE_FRAME_PACER, framePacer);
		}

		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("version", (Supplier<String>) () -> bridge.version);
		settings.put("backend", (Supplier<String>) bridge.backendSupplier);
		settings.put("statusSummary", (Supplier<String>) bridge::statusSummary);
		settings.put("mixinHealth", (Supplier<String>) bridge::mixinHealthSummary);
		settings.put("meshShaderActive", (BooleanSupplier) bridge::isVulkanMeshShaderActive);
		settings.put("options", (Supplier<List<Map<String, Object>>>) bridge::optionsAsMaps);
		settings.put("get", (Predicate<String>) bridge::getBoolean);
		settings.put("set", (BiPredicate<String, Boolean>) bridge::setBoolean);
		settings.put("openConfigScreen", (Function<Object, Object>) parent ->
			new EphemeraSettingsScreen(parent instanceof Screen s ? s : null));
		share.put(SHARE_SETTINGS, Map.copyOf(settings));
		return bridge;
	}

	private static Map<String, Object> buildFramePacerBridge() {
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodHandle reportAurora = lookup.findStatic(EphemeraPacerRuntime.class, "onAuroraReport",
				MethodType.methodType(void.class, boolean.class, int.class, long.class));
			MethodHandle beforeWait = lookup.findStatic(EphemeraPacerRuntime.class, "onAuroraBeforeWait",
				MethodType.methodType(void.class, long.class));
			MethodHandle afterPark = lookup.findStatic(EphemeraPacerRuntime.class, "onAuroraAfterPark",
				MethodType.methodType(void.class, long.class, long.class));
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("protocolVersion", 2);
			map.put("reportAurora", reportAurora);
			map.put("beforeWait", beforeWait);
			map.put("afterPark", afterPark);
			map.put("isVulkanAsyncPresentActive", (BooleanSupplier) EphemeraPacerRuntime::isVulkanAsyncPresentActive);
			map.put("recommendedOvershootMicros", (IntSupplier) EphemeraPacerRuntime::recommendedOvershootMicros);
			map.put("setAuroraLimits", (BiConsumer<IntUnaryOperator, BooleanSupplier>)
				EphemeraPacerRuntime::setAuroraLimits);
			map.put("ownsAuroraPacing", (BooleanSupplier) EphemeraPacerRuntime::ownsAuroraPacing);
			map.put("statusLine", (Supplier<String>) EphemeraPacerRuntime::statusLine);
			return Map.copyOf(map);
		} catch (Throwable t) {
			EphemeraClient.LOGGER.warn("Could not publish Ephemera frame_pacer ObjectShare handles: {}", t.toString());
			return Map.of();
		}
	}

	@Override
	public List<EphemeraOptionDescriptor> options() {
		EphemeraConfig c = EphemeraCommon.config();
		return List.of(
			new EphemeraOptionDescriptor("terrainEngineEnabled", "Regional Terrain Engine",
				"Batches visible terrain sections by region with multi-draw submission.", "Terrain", c.terrainEngineEnabled, false),
			new EphemeraOptionDescriptor("terrainVulkanMeshShaders", "Vulkan Mesh Shaders",
				"Single-pass VK_EXT_mesh_shader Task+Mesh GPU terrain pipeline on supported GPUs.", "Terrain", c.terrainVulkanMeshShaders, false),
			new EphemeraOptionDescriptor("terrainDirectionalFaceCulling", "Directional Face Culling",
				"Skips terrain face buckets facing away from the camera before draw submission.", "Terrain", c.terrainDirectionalFaceCulling, false),
			new EphemeraOptionDescriptor("terrainFogOcclusion", "Fog Occlusion Culling",
				"Skips terrain sections completely obscured by environmental or render-distance fog.", "Terrain", c.terrainFogOcclusion, false),
			new EphemeraOptionDescriptor("terrainAnimateOnlyVisibleTextures", "Animate Visible Sprites Only",
				"Uploads animated block-atlas textures only when visible in drawn sections or items.", "Terrain", c.terrainAnimateOnlyVisibleTextures, false),
			new EphemeraOptionDescriptor("entityOcclusionCulling", "Entity Occlusion Culling",
				"Skips rendering entities hidden behind solid terrain.", "Culling", c.entityOcclusionCulling, false),
			new EphemeraOptionDescriptor("blockEntityOcclusionCulling", "Block Entity Occlusion Culling",
				"Skips rendering chests and other block entities hidden behind solid terrain.", "Culling", c.blockEntityOcclusionCulling, false),
			new EphemeraOptionDescriptor("particleCulling", "Particle Frustum & Occlusion Culling",
				"Skips particles outside the view frustum or occluded behind solid blocks.", "Culling", c.particleCulling, false),
			new EphemeraOptionDescriptor("framePacerEnabled", "Adaptive Frame Pacer & Upload Budget",
				"Smooths frame cadence and paces chunk uploads to eliminate frametime spikes (cooperates with Aurora Client).", "Pacing", c.framePacerEnabled, false),
			new EphemeraOptionDescriptor("memoryLeakGuard", "Memory Leak Guard & Deduplication",
				"Deduplicates block states/models, shrinks bloated off-heap buffers, and sweeps caches on world unload.", "Memory", c.memoryLeakGuard, false),
			new EphemeraOptionDescriptor("visualEnhancedAmbientOcclusion", "Enhanced Ambient Occlusion",
				"Refines block corner ambient occlusion contrast with negligible frame cost.", "Visuals", c.visualEnhancedAmbientOcclusion, false),
			new EphemeraOptionDescriptor("visualDynamicLights", "Dynamic Entity Lights",
				"Smoothly illuminates held light sources and glowing entities without shaders.", "Visuals", c.visualDynamicLights, false),
			new EphemeraOptionDescriptor("visualSmoothChunkFade", "Smooth Chunk Fade-In",
				"Gently fades newly loaded terrain sections into view.", "Visuals", c.visualSmoothChunkFade, false),
			new EphemeraOptionDescriptor("diagnosticsHudOverlay", "Ephemera Diagnostics HUD",
				"Displays compact frame-time, backend, and terrain batching diagnostics.", "Diagnostics", c.diagnosticsHudOverlay, false)
		);
	}

	private List<Map<String, Object>> optionsAsMaps() {
		List<EphemeraOptionDescriptor> list = options();
		List<Map<String, Object>> out = new ArrayList<>(list.size());
		for (EphemeraOptionDescriptor opt : list) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", opt.key());
			m.put("label", opt.label());
			m.put("description", opt.description());
			m.put("category", opt.category());
			m.put("value", opt.value());
			m.put("defaultValue", defaultBoolean(opt.key()));
			m.put("requiresRestart", opt.requiresRestart());
			out.add(Map.copyOf(m));
		}
		return List.copyOf(out);
	}

	private static boolean defaultBoolean(String key) {
		return readBoolean(DEFAULTS, key);
	}

	@Override
	public boolean getBoolean(String key) {
		return readBoolean(EphemeraCommon.config(), key);
	}

	private static boolean readBoolean(EphemeraConfig c, String key) {
		return switch (key) {
			case "terrainEngineEnabled" -> c.terrainEngineEnabled;
			case "terrainVulkanMeshShaders" -> c.terrainVulkanMeshShaders;
			case "terrainDirectionalFaceCulling" -> c.terrainDirectionalFaceCulling;
			case "terrainFogOcclusion" -> c.terrainFogOcclusion;
			case "terrainAnimateOnlyVisibleTextures" -> c.terrainAnimateOnlyVisibleTextures;
			case "entityOcclusionCulling" -> c.entityOcclusionCulling;
			case "blockEntityOcclusionCulling" -> c.blockEntityOcclusionCulling;
			case "particleCulling" -> c.particleCulling;
			case "framePacerEnabled" -> c.framePacerEnabled;
			case "memoryLeakGuard" -> c.memoryLeakGuard;
			case "visualEnhancedAmbientOcclusion" -> c.visualEnhancedAmbientOcclusion;
			case "visualDynamicLights" -> c.visualDynamicLights;
			case "visualSmoothChunkFade" -> c.visualSmoothChunkFade;
			case "diagnosticsHudOverlay" -> c.diagnosticsHudOverlay;
			default -> false;
		};
	}

	@Override
	public boolean setBoolean(String key, boolean value) {
		EphemeraConfig c = EphemeraCommon.config();
		boolean updated = switch (key) {
			case "terrainEngineEnabled" -> { c.terrainEngineEnabled = value; yield true; }
			case "terrainVulkanMeshShaders" -> { c.terrainVulkanMeshShaders = value; yield true; }
			case "terrainDirectionalFaceCulling" -> { c.terrainDirectionalFaceCulling = value; yield true; }
			case "terrainFogOcclusion" -> { c.terrainFogOcclusion = value; yield true; }
			case "terrainAnimateOnlyVisibleTextures" -> { c.terrainAnimateOnlyVisibleTextures = value; yield true; }
			case "entityOcclusionCulling" -> { c.entityOcclusionCulling = value; yield true; }
			case "blockEntityOcclusionCulling" -> { c.blockEntityOcclusionCulling = value; yield true; }
			case "particleCulling" -> { c.particleCulling = value; yield true; }
			case "framePacerEnabled" -> { c.framePacerEnabled = value; yield true; }
			case "memoryLeakGuard" -> { c.memoryLeakGuard = value; yield true; }
			case "visualEnhancedAmbientOcclusion" -> { c.visualEnhancedAmbientOcclusion = value; VisualsRuntime.invalidate(); yield true; }
			case "visualDynamicLights" -> { c.visualDynamicLights = value; VisualsRuntime.invalidate(); yield true; }
			case "visualSmoothChunkFade" -> { c.visualSmoothChunkFade = value; VisualsRuntime.invalidate(); yield true; }
			case "diagnosticsHudOverlay" -> { c.diagnosticsHudOverlay = value; yield true; }
			default -> false;
		};
		if (updated) {
			GuiFeatures.init();
			save();
		}
		return updated;
	}

	@Override
	public void save() {
		EphemeraCommon.saveConfig();
	}

	@Override
	public boolean isVulkanMeshShaderActive() {
		return RenderPlatform.isVulkanMeshShaderActive();
	}

	@Override
	public String statusSummary() {
		String backend = backendSupplier.get();
		String mesh = isVulkanMeshShaderActive()
			? RenderPlatform.meshShaderLabel()
			: ("VULKAN".equals(backend) ? "Vulkan Multi-Draw Indirect" : "OpenGL Multi-Draw");
		return "Ephemera " + version + " · " + backend + " · " + mesh;
	}

	@Override
	public String mixinHealthSummary() {
		return MixinHealth.summary();
	}
}
