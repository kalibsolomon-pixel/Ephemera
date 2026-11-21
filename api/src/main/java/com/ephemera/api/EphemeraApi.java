package com.ephemera.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public entry point for Aurora Client and third-party mods to discover and
 * integrate with Ephemera without coupling to internal rendering classes.
 */
public final class EphemeraApi {
	private static final AtomicReference<Service> INSTANCE = new AtomicReference<>();

	private EphemeraApi() {}

	public static Optional<Service> get() {
		return Optional.ofNullable(INSTANCE.get());
	}

	public static boolean isAvailable() {
		return INSTANCE.get() != null;
	}

	public static void register(Service service) {
		INSTANCE.set(service);
	}

	public interface Service {
		String version();

		EphemeraCapabilities capabilities();

		EphemeraVisibilityProvider visibility();

		EphemeraShaderHooks shaders();

		default EphemeraSettingsBridge settings() {
			return null;
		}
	}
}
