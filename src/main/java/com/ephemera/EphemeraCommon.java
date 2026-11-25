package com.ephemera;

import com.ephemera.core.config.ConfigStore;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.engine.common.EntityClassIndex;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class EphemeraCommon implements ModInitializer {
	public static final String MOD_ID = "ephemera";
	public static final Logger LOGGER = LoggerFactory.getLogger("Ephemera");

	private static volatile EphemeraConfig config;
	private static volatile ConfigStore configStore;

	public static EphemeraConfig config() {
		EphemeraConfig current = config;
		if (current == null) {
			synchronized (EphemeraCommon.class) {
				if (config == null) {
					Path path = FabricLoader.getInstance().getConfigDir().resolve("ephemera.json");
					configStore = new ConfigStore(path);
					config = configStore.load();
				}
				current = config;
			}
		}
		return current;
	}

	public static void saveConfig() {
		ConfigStore store = configStore;
		EphemeraConfig current = config;
		if (store != null && current != null) {
			store.save(current);
		}
	}

	@Override
	public void onInitialize() {
		config();
		// Warm the collision fast-path class indexes off the ticking thread when the fast paths
		// are enabled; blocks registered later by other mods fall back to lazy population on
		// first use.
		if (config().engineFastCollisions) {
			EntityClassIndex.prepopulate();
		}
		String mc = FabricLoader.getInstance().getModContainer("minecraft")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		LOGGER.info("Ephemera common engine initialized (mc {})", mc);
	}
}
