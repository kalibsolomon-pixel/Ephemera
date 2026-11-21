package com.ephemera.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic, corruption-safe JSON configuration loader/saver.
 */
public final class ConfigStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("EphemeraConfig");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	private final Path path;

	public ConfigStore(Path path) {
		this.path = path;
	}

	public EphemeraConfig load() {
		if (!Files.exists(path)) {
			EphemeraConfig defaults = new EphemeraConfig();
			save(defaults);
			return defaults;
		}
		try {
			String raw = Files.readString(path, StandardCharsets.UTF_8);
			EphemeraConfig parsed = GSON.fromJson(raw, EphemeraConfig.class);
			if (parsed == null) {
				return new EphemeraConfig();
			}
			sanitize(parsed);
			return parsed;
		} catch (Exception e) {
			LOGGER.warn("Failed to read {}, using defaults: {}", path, e.getMessage());
			return new EphemeraConfig();
		}
	}

	public void save(EphemeraConfig config) {
		sanitize(config);
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
			Files.writeString(tmp, GSON.toJson(config) + "\n", StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}: {}", path, e.getMessage());
		}
	}

	public static void sanitize(EphemeraConfig c) {
		if (c.leavesCullingMode == null) c.leavesCullingMode = EphemeraConfig.LeavesCullingMode.DEPTH;
		c.leavesCullingDepth = Math.max(1, Math.min(4, c.leavesCullingDepth));
		c.chunkBuilderThreads = Math.max(0, Math.min(32, c.chunkBuilderThreads));
		c.maxSectionUploadsPerFrameMs = Math.max(1, Math.min(32, c.maxSectionUploadsPerFrameMs));
		c.guiFontAtlasSize = Math.max(256, Math.min(4096, c.guiFontAtlasSize));
		c.guiGlintFps = Math.max(10, Math.min(240, c.guiGlintFps));
		c.maxParticlesPerSheet = Math.max(512, Math.min(65536, c.maxParticlesPerSheet));
		c.unfocusedFpsLimit = Math.max(5, Math.min(240, c.unfocusedFpsLimit));
		c.visualDitherStrength = Math.max(0.0f, Math.min(2.0f, c.visualDitherStrength));
		c.visualAoStrength = Math.max(0.5f, Math.min(1.5f, c.visualAoStrength));
		c.visualChunkFadeDurationSeconds = Math.max(0.0f, Math.min(2.0f, c.visualChunkFadeDurationSeconds));
		c.visualDynamicLightsUpdateIntervalMs = Math.max(0, Math.min(100, c.visualDynamicLightsUpdateIntervalMs));
		c.visualAnisotropicLevel = switch (c.visualAnisotropicLevel) {
			case 0, 2, 4, 8, 16 -> c.visualAnisotropicLevel;
			default -> 4;
		};
	}
}
