package com.ephemera;

import com.ephemera.api.EphemeraApi;
import com.ephemera.api.EphemeraCapabilities;
import com.ephemera.api.EphemeraSettingsBridge;
import com.ephemera.api.EphemeraShaderHooks;
import com.ephemera.api.EphemeraVisibilityProvider;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.render.culling.EphemeraVisibilityService;
import com.ephemera.render.gui.GuiFeatures;
import com.ephemera.visuals.IrisCompatService;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EphemeraClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("EphemeraClient");

	private static volatile EphemeraVisibilityProvider visibilityProvider = NoopVisibilityProvider.INSTANCE;
	private static volatile EphemeraShaderHooks shaderHooks = DefaultShaderHooks.INSTANCE;
	private static volatile String activeBackend = "UNKNOWN";

	@Override
	public void onInitializeClient() {
		EphemeraConfig cfg = EphemeraCommon.config();
		String version = FabricLoader.getInstance()
			.getModContainer(EphemeraCommon.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("1.0.0");

		EphemeraSettingsBridge settingsBridge = EphemeraClientBridge.register(version, EphemeraClient::resolveBackend);

		EphemeraApi.register(new EphemeraApi.Service() {
			@Override
			public String version() {
				return version;
			}

			@Override
			public EphemeraCapabilities capabilities() {
				return new EphemeraCapabilities(
					cfg.terrainEngineEnabled,
					cfg.entityOcclusionCulling,
					cfg.blockEntityOcclusionCulling,
					cfg.guiLayerIndexing || cfg.guiAnimatedItemBaking,
					cfg.guiFastLtrText || cfg.guiMergeTextDecorations,
					cfg.particleCulling,
					cfg.backendGlDispatchCache || cfg.backendVulkanDispatchCache,
					cfg.engineFastLighting,
					cfg.visualSmoothFog && !shaderHooks.isExternalShaderPackActive(),
					cfg.visualGradientDithering && !shaderHooks.isExternalShaderPackActive(),
					cfg.visualDynamicLights && !shaderHooks.isExternalShaderPackActive(),
					resolveBackend()
				);
			}

			@Override
			public EphemeraVisibilityProvider visibility() {
				return visibilityProvider;
			}

			@Override
			public EphemeraShaderHooks shaders() {
				return shaderHooks;
			}

			@Override
			public EphemeraSettingsBridge settings() {
				return settingsBridge;
			}
		});

		IrisCompatService.init();
		EphemeraVisibilityService.init();
		GuiFeatures.init();

		if (cfg.engineWorkerPriorityIsolation) {
			try {
				Thread.currentThread().setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
			} catch (SecurityException ignored) {
			}
		}

		LOGGER.info("Ephemera {} initialized ({})", version, MixinHealth.summary());
	}

	private static String resolveBackend() {
		String cur = activeBackend;
		if (!"UNKNOWN".equals(cur)) {
			return cur;
		}
		try {
			var dev = RenderSystem.tryGetDevice();
			if (dev != null) {
				String info = String.valueOf(dev.getDeviceInfo());
				String detected = info.toUpperCase().contains("VULKAN") ? "VULKAN" : "OPENGL";
				activeBackend = detected;
				return detected;
			}
		} catch (Throwable ignored) {}
		return cur;
	}

	public static void setVisibilityProvider(EphemeraVisibilityProvider provider) {
		visibilityProvider = provider != null ? provider : NoopVisibilityProvider.INSTANCE;
	}

	public static void setShaderHooks(EphemeraShaderHooks hooks) {
		shaderHooks = hooks != null ? hooks : DefaultShaderHooks.INSTANCE;
	}

	public static void setActiveBackend(String backend) {
		activeBackend = backend != null ? backend : "UNKNOWN";
	}

	private enum NoopVisibilityProvider implements EphemeraVisibilityProvider {
		INSTANCE;

		@Override public boolean isSectionVisible(int sectionX, int sectionY, int sectionZ) { return true; }
		@Override public boolean isBoxOccluded(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { return false; }
		@Override public long terrainRevisionStamp() { return 0L; }
		@Override public int visibleSectionCount() { return 0; }
		@Override public int occludedEntityCount() { return 0; }
		@Override public int occludedBlockEntityCount() { return 0; }
	}

	private enum DefaultShaderHooks implements EphemeraShaderHooks {
		INSTANCE;

		@Override public boolean isExternalShaderPackActive() { return false; }
		@Override public boolean ownsSmoothFog() { return EphemeraCommon.config().visualSmoothFog; }
		@Override public boolean ownsGradientDithering() { return EphemeraCommon.config().visualGradientDithering; }
		@Override public boolean ownsDynamicLighting() { return EphemeraCommon.config().visualDynamicLights; }
	}
}
