package com.ephemera.visuals;

import com.ephemera.EphemeraClient;
import com.ephemera.EphemeraCommon;
import com.ephemera.api.EphemeraShaderHooks;
import com.ephemera.core.config.EphemeraConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Detects an active Iris / Oculus shader pack through its public v0 API via reflection, with no
 * compile-time dependency. While a pack is active every Ephemera visual stands down so the pack
 * owns fog, dithering, lighting, and the core shaders outright.
 */
public final class IrisCompatService implements EphemeraShaderHooks {
	public static final IrisCompatService INSTANCE = new IrisCompatService();

	private static final String[] MOD_IDS = {"iris", "oculus"};
	private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
	private static final long POLL_INTERVAL_NS = 250_000_000L;

	private static volatile boolean registered;

	private final MethodHandle getInstance;
	private final MethodHandle isShaderPackInUse;
	private volatile boolean cachedActive;
	private volatile long cachedAtNs;

	private IrisCompatService() {
		MethodHandle instance = null;
		MethodHandle inUse = null;
		if (shaderModLoaded()) {
			try {
				Class<?> api = Class.forName(API_CLASS, false, IrisCompatService.class.getClassLoader());
				MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
				inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				EphemeraClient.LOGGER.warn("Iris is installed but its API could not be bound; shader pack detection disabled: {}", e.toString());
			}
		}
		this.getInstance = instance;
		this.isShaderPackInUse = inUse;
	}

	/** Idempotent. Installs this service as Ephemera's shader coordination hooks. */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		EphemeraClient.setShaderHooks(INSTANCE);
	}

	private static boolean shaderModLoaded() {
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : MOD_IDS) {
			if (loader.isModLoaded(id)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isExternalShaderPackActive() {
		if (isShaderPackInUse == null) {
			return false;
		}
		long now = System.nanoTime();
		if (now - cachedAtNs < POLL_INTERVAL_NS) {
			return cachedActive;
		}
		boolean active;
		try {
			active = (boolean) isShaderPackInUse.invoke(getInstance.invoke());
		} catch (Throwable t) {
			active = false;
		}
		cachedActive = active;
		cachedAtNs = now;
		return active;
	}

	@Override
	public boolean ownsSmoothFog() {
		return config().visualSmoothFog && !isExternalShaderPackActive();
	}

	@Override
	public boolean ownsGradientDithering() {
		return config().visualGradientDithering && !isExternalShaderPackActive();
	}

	@Override
	public boolean ownsDynamicLighting() {
		return config().visualDynamicLights && !isExternalShaderPackActive();
	}

	private static EphemeraConfig config() {
		return EphemeraCommon.config();
	}
}
