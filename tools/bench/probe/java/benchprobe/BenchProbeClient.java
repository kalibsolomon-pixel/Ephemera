package benchprobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.management.ThreadMXBean;
import jdk.jfr.Recording;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.particles.ParticleTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Competitor-agnostic, near-zero-overhead multi-scenario benchmark probe.
 * Measures exact frame times, 1%/0.1% lows, render-thread allocations, GC pauses,
 * chunk rebuild burst latency, JFR CPU profiles, and captures reference screenshots.
 */
public final class BenchProbeClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("EphemeraBenchProbe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Path OUT_DIR = Path.of(System.getProperty("ephemera.bench.out", "build/bench/out"));
	private static final String LABEL = System.getProperty("ephemera.bench.label", "unknown");
	private static final String BACKEND = System.getProperty("ephemera.bench.backend", "OPENGL").toLowerCase();
	private static final boolean JFR_ENABLED = Boolean.parseBoolean(System.getProperty("ephemera.bench.jfr", "true"));
	private static final int DURATION_SEC = Integer.parseInt(System.getProperty("ephemera.bench.durationSec", "6"));
	private static final int VISTA_RD = Integer.parseInt(System.getProperty("ephemera.bench.rd", "20"));
	private static final String SCENE1_NAME = "terrain_vista_rd" + VISTA_RD;

	private enum Phase {
		WAIT_FOR_WORLD,
		INIT_WORLD,
		WARMUP,
		SCENE1_SETUP,
		SCENE1_SETTLE,
		SCENE1_MEASURE,
		SCENE2_SETUP,
		SCENE2_SETTLE,
		SCENE2_MEASURE,
		SCENE3_SETUP,
		SCENE3_MEASURE,
		SCENE4_SETUP,
		SCENE4_SETTLE,
		SCENE4_MEASURE,
		FINISH,
		DONE
	}

	private static Phase phase = Phase.WAIT_FOR_WORLD;
	private static long phaseStartNs = 0L;
	private static int phaseTicks = 0;

	// Pre-allocated frame timing buffer (handles up to 200,000 frames without resizing)
	private static final long[] FRAME_DELTAS = new long[200_000];
	private static int frameCount = 0;
	private static long lastFrameNs = 0L;
	private static boolean measuringFrames = false;

	// Camera sweep state for Scene 1
	private static boolean sweepingCamera = false;
	private static long sweepStartNs = 0L;

	// Per-scene telemetry baselines
	private static long sceneStartAllocBytes = 0L;
	private static long sceneStartGcCount = 0L;
	private static long sceneStartGcTimeMs = 0L;
	private static long sceneMeasureStartNs = 0L;
	private static long renderThreadId = -1L;
	private static final ThreadMXBean THREAD_MX =
		ManagementFactory.getThreadMXBean() instanceof ThreadMXBean t ? t : null;

	// Scene 3 chunk burst tracking
	private static long chunkBurstStartNs = 0L;
	private static long chunkBurstDoneNs = 0L;

	// Particle spawn toggle for Scene 2
	private static boolean spawnStressParticles = false;

	private static Recording activeRecording = null;
	private static final Map<String, Map<String, Object>> SCENE_RESULTS = new LinkedHashMap<>();

	@Override
	public void onInitializeClient() {
		LOGGER.info("BenchProbe active: label={} backend={} duration={}s jfr={}", LABEL, BACKEND, DURATION_SEC, JFR_ENABLED);
	}

	public static void onRenderFrameEnd() {
		if (!measuringFrames) {
			return;
		}
		long now = System.nanoTime();
		if (lastFrameNs != 0L && frameCount < FRAME_DELTAS.length) {
			long dt = now - lastFrameNs;
			if (dt > 0L) {
				FRAME_DELTAS[frameCount++] = dt;
			}
		}
		lastFrameNs = now;

		if (sweepingCamera) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				double elapsedSec = (now - sweepStartNs) / 1_000_000_000.0;
				float yaw = (float) ((elapsedSec * 36.0) % 360.0);
				mc.player.setYRot(yaw);
				mc.player.setXRot(16.0f);
			}
		}
	}

	public static void onClientTick() {
		Minecraft mc = Minecraft.getInstance();
		if (phase == Phase.DONE) {
			return;
		}
		if (renderThreadId == -1L) {
			renderThreadId = Thread.currentThread().threadId();
		}

		long now = System.nanoTime();
		phaseTicks++;

		switch (phase) {
			case WAIT_FOR_WORLD -> {
				if (mc.level != null && mc.player != null && mc.getConnection() != null) {
					enter(Phase.INIT_WORLD, now);
				}
			}
			case INIT_WORLD -> {
				if (phaseTicks == 1) {
					mc.options.enableVsync().set(false);
					mc.options.framerateLimit().set(260);
					mc.options.guiScale().set(3);
					mc.options.renderDistance().set(VISTA_RD);
					mc.gui.setScreen(null);
					send(mc, "gamemode creative");
					send(mc, "difficulty peaceful");
					send(mc, "time set 6000");
					send(mc, "weather clear 999999");
					send(mc, "gamerule doDaylightCycle false");
					send(mc, "gamerule doWeatherCycle false");
					send(mc, "gamerule doMobSpawning false");
					send(mc, "tp @s 0.5 128.0 0.5 0 16");
				}
				if (phaseTicks >= 20) {
					enter(Phase.WARMUP, now);
				}
			}
			case WARMUP -> {
				// Wait for initial chunk compilation + JIT warmup (at least 4s, up to 12s or 24s at high RD)
				double elapsed = (now - phaseStartNs) / 1e9;
				double maxWait = VISTA_RD > 20 ? 24.0 : 12.0;
				boolean chunksReady = mc.levelRenderer != null && mc.levelRenderer.hasRenderedAllSections();
				if (elapsed >= maxWait || (elapsed >= 4.0 && chunksReady)) {
					enter(Phase.SCENE1_SETUP, now);
				}
			}
			case SCENE1_SETUP -> {
				mc.gui.setScreen(null);
				mc.options.renderDistance().set(VISTA_RD);
				if (mc.player != null) {
					mc.player.absSnapTo(0.5, 128.0, 0.5, 0.0f, 16.0f);
				}
				enter(Phase.SCENE1_SETTLE, now);
			}
			case SCENE1_SETTLE -> {
				if (phaseTicks == 10) {
					captureScreenshot(mc, "scene1_terrain_vista");
				}
				if (phaseTicks >= 30) {
					sweepingCamera = true;
					sweepStartNs = now;
					beginMeasure(SCENE1_NAME, now);
					enter(Phase.SCENE1_MEASURE, now);
				}
			}
			case SCENE1_MEASURE -> {
				if ((now - phaseStartNs) >= DURATION_SEC * 1_000_000_000L) {
					sweepingCamera = false;
					endMeasure(SCENE1_NAME, now, null);
					enter(Phase.SCENE2_SETUP, now);
				}
			}
			case SCENE2_SETUP -> {
				if (phaseTicks == 1) {
					mc.gui.setScreen(null);
					mc.options.renderDistance().set(16);
					return;
				}
				if (phaseTicks < 6) {
					return;
				}
				// Build deterministic arena after view-distance change settles so allChanged() never races /fill.
				send(mc, "kill @e[type=!player]");
				send(mc, "tp @s 0.5 130.0 0.5 0 12");
				// Platform & occlusion wall at z=+10 (40 entities behind wall at z=+14, 40 visible at z=+5)
				send(mc, "fill -18 129 -2 18 129 22 minecraft:stone");
				send(mc, "fill -16 130 10 16 136 10 minecraft:stone");
				// Visible & occluded chests
				send(mc, "fill -8 130 6 7 131 6 minecraft:chest[facing=north]");
				send(mc, "fill -8 130 14 7 131 14 minecraft:chest[facing=north]");
				// Spawn 40 visible pigs and 40 wall-occluded pigs (NoAI so deterministic!)
				for (int i = 0; i < 40; i++) {
					double x = -7.5 + (i % 16) * 1.0;
					double zVis = 4.5 + (i / 16) * 0.9;
					double zOcc = 12.5 + (i / 16) * 0.9;
					send(mc, String.format("summon minecraft:pig %.1f 130.0 %.1f {NoAI:1b,Silent:1b}", x, zVis));
					send(mc, String.format("summon minecraft:pig %.1f 130.0 %.1f {NoAI:1b,Silent:1b}", x, zOcc));
				}
				// Enchanted PvP hotbar & armor (stresses GUI item atlas + glint!)
				send(mc, "item replace entity @s armor.head with minecraft:netherite_helmet[minecraft:enchantments={\"minecraft:protection\":4}]");
				send(mc, "item replace entity @s armor.chest with minecraft:netherite_chestplate[minecraft:enchantments={\"minecraft:protection\":4}]");
				send(mc, "item replace entity @s armor.legs with minecraft:netherite_leggings[minecraft:enchantments={\"minecraft:protection\":4}]");
				send(mc, "item replace entity @s armor.feet with minecraft:netherite_boots[minecraft:enchantments={\"minecraft:protection\":4}]");
				send(mc, "item replace entity @s hotbar.0 with minecraft:netherite_sword[minecraft:enchantments={\"minecraft:sharpness\":5}]");
				send(mc, "item replace entity @s hotbar.1 with minecraft:enchanted_golden_apple 64");
				send(mc, "item replace entity @s hotbar.2 with minecraft:bow[minecraft:enchantments={\"minecraft:power\":5}]");
				send(mc, "item replace entity @s hotbar.3 with minecraft:trident[minecraft:enchantments={\"minecraft:loyalty\":3}]");
				send(mc, "item replace entity @s hotbar.4 with minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
				send(mc, "item replace entity @s hotbar.5 with minecraft:compass");
				send(mc, "item replace entity @s hotbar.6 with minecraft:clock");
				spawnStressParticles = true;
				enter(Phase.SCENE2_SETTLE, now);
			}
			case SCENE2_SETTLE -> {
				tickStressParticles(mc);
				if (mc.player != null) {
					mc.player.absSnapTo(0.5, 130.0, 0.5, 0.0f, 12.0f);
				}
				if (phaseTicks == 20) {
					captureScreenshot(mc, "scene2_dense_crowd");
				}
				if (phaseTicks >= 35) {
					beginMeasure("dense_world_crowd", now);
					enter(Phase.SCENE2_MEASURE, now);
				}
			}
			case SCENE2_MEASURE -> {
				tickStressParticles(mc);
				if (mc.player != null) {
					mc.player.absSnapTo(0.5, 130.0, 0.5, 0.0f, 12.0f);
				}
				if ((now - phaseStartNs) >= DURATION_SEC * 1_000_000_000L) {
					spawnStressParticles = false;
					endMeasure("dense_world_crowd", now, null);
					enter(Phase.SCENE3_SETUP, now);
				}
			}
			case SCENE3_SETUP -> {
				// Chunk streaming / meshing burst: jump 384 blocks away at VISTA_RD and measure full chunk build + 1% lows!
				mc.options.renderDistance().set(VISTA_RD);
				send(mc, "kill @e[type=!player]");
				send(mc, "tp @s 384.5 135.0 384.5 45 20");
				chunkBurstStartNs = now;
				chunkBurstDoneNs = 0L;
				beginMeasure("chunk_rebuild_burst", now);
				enter(Phase.SCENE3_MEASURE, now);
			}
			case SCENE3_MEASURE -> {
				if (chunkBurstDoneNs == 0L && phaseTicks >= 5
					&& mc.levelRenderer != null && mc.levelRenderer.hasRenderedAllSections()) {
					chunkBurstDoneNs = now;
				}
				if ((now - phaseStartNs) >= DURATION_SEC * 1_000_000_000L) {
					double burstMs = chunkBurstDoneNs > 0L
						? (chunkBurstDoneNs - chunkBurstStartNs) / 1_000_000.0
						: (now - chunkBurstStartNs) / 1_000_000.0;
					Map<String, Object> extra = new LinkedHashMap<>();
					extra.put("chunkBurstCompleteMs", round3(burstMs));
					extra.put("chunkBurstCompletedInWindow", chunkBurstDoneNs > 0L);
					endMeasure("chunk_rebuild_burst", now, extra);
					enter(Phase.SCENE4_SETUP, now);
				}
			}
			case SCENE4_SETUP -> {
				mc.options.renderDistance().set(16);
				if (mc.player != null) {
					mc.gui.setScreen(new InventoryScreen(mc.player));
				}
				enter(Phase.SCENE4_SETTLE, now);
			}
			case SCENE4_SETTLE -> {
				if (phaseTicks >= 20) {
					beginMeasure("gui_pvp_hud", now);
					enter(Phase.SCENE4_MEASURE, now);
				}
			}
			case SCENE4_MEASURE -> {
				if ((now - phaseStartNs) >= DURATION_SEC * 1_000_000_000L) {
					endMeasure("gui_pvp_hud", now, null);
					mc.gui.setScreen(null);
					enter(Phase.FINISH, now);
				}
			}
			case FINISH -> {
				writeResults(mc);
				enter(Phase.DONE, now);
				mc.stop();
			}
			case DONE -> {}
		}
	}

	private static void tickStressParticles(Minecraft mc) {
		if (!spawnStressParticles || mc.level == null) return;
		for (int i = 0; i < 60; i++) {
			double x = -5.0 + (i % 10) * 1.0;
			double y = 130.5 + (i / 20) * 0.6;
			// Half visible in front, half hidden behind the wall at z=10
			double z = (i < 30) ? 5.0 : 13.0;
			mc.level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.01, 0.0);
		}
	}

	private static void beginMeasure(String sceneName, long now) {
		frameCount = 0;
		lastFrameNs = 0L;
		sceneMeasureStartNs = now;
		sceneStartAllocBytes = currentRenderAllocBytes();
		sceneStartGcCount = totalGcCount();
		sceneStartGcTimeMs = totalGcTimeMs();
		if (JFR_ENABLED) {
			try {
				activeRecording = new Recording();
				activeRecording.setName("bench-" + sceneName);
				activeRecording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
				activeRecording.enable("jdk.GarbageCollection");
				activeRecording.start();
			} catch (Throwable t) {
				LOGGER.warn("Could not start JFR for {}: {}", sceneName, t.getMessage());
				activeRecording = null;
			}
		}
		measuringFrames = true;
	}

	private static void endMeasure(String sceneName, long now, Map<String, Object> extra) {
		measuringFrames = false;
		if (activeRecording != null) {
			try {
				Files.createDirectories(OUT_DIR);
				Path jfrPath = OUT_DIR.resolve("jfr-" + LABEL + "-" + BACKEND + "-" + sceneName + ".jfr");
				activeRecording.stop();
				activeRecording.dump(jfrPath);
				activeRecording.close();
			} catch (Throwable t) {
				LOGGER.warn("Failed to dump JFR for {}: {}", sceneName, t.getMessage());
			}
			activeRecording = null;
		}

		double elapsedSec = Math.max(0.001, (now - sceneMeasureStartNs) / 1e9);
		long allocDelta = Math.max(0L, currentRenderAllocBytes() - sceneStartAllocBytes);
		double allocMbPerSec = (allocDelta / (1024.0 * 1024.0)) / elapsedSec;
		long gcCount = totalGcCount() - sceneStartGcCount;
		long gcTimeMs = totalGcTimeMs() - sceneStartGcTimeMs;
		double usedHeapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0);

		int n = frameCount;
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("samples", n);
		if (n > 0) {
			long[] sorted = Arrays.copyOf(FRAME_DELTAS, n);
			Arrays.sort(sorted);
			long totalNs = 0L;
			for (int i = 0; i < n; i++) totalNs += sorted[i];
			double meanMs = (totalNs / (double) n) / 1e6;
			double avgFps = (n * 1e9) / totalNs;
			double medianMs = sorted[Math.min(n - 1, (int) Math.ceil(0.50 * n) - 1)] / 1e6;
			double p95Ms = sorted[Math.min(n - 1, (int) Math.ceil(0.95 * n) - 1)] / 1e6;
			double p99Ms = sorted[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)] / 1e6;
			double onePctLow = tailFps(sorted, n, 0.01);
			double pointOnePctLow = tailFps(sorted, n, 0.001);

			row.put("avgFps", round2(avgFps));
			row.put("meanMs", round3(meanMs));
			row.put("medianMs", round3(medianMs));
			row.put("p95Ms", round3(p95Ms));
			row.put("p99Ms", round3(p99Ms));
			row.put("onePercentLowFps", round2(onePctLow));
			row.put("pointOnePercentLowFps", round2(pointOnePctLow));
		} else {
			row.put("avgFps", 0.0);
			row.put("meanMs", 0.0);
			row.put("medianMs", 0.0);
			row.put("p95Ms", 0.0);
			row.put("p99Ms", 0.0);
			row.put("onePercentLowFps", 0.0);
			row.put("pointOnePercentLowFps", 0.0);
		}
		row.put("allocMbPerSec", round2(allocMbPerSec));
		row.put("gcCount", gcCount);
		row.put("gcTimeMs", gcTimeMs);
		row.put("usedHeapMb", round2(usedHeapMb));
		if (extra != null) {
			row.putAll(extra);
		}
		SCENE_RESULTS.put(sceneName, row);
		LOGGER.info("[BENCH {} {}] {} -> {} FPS (med {}ms, p99 {}ms, 1%L {}, alloc {} MB/s)",
			LABEL, BACKEND, sceneName, row.get("avgFps"), row.get("medianMs"), row.get("p99Ms"),
			row.get("onePercentLowFps"), row.get("allocMbPerSec"));
	}

	private static void captureScreenshot(Minecraft mc, String name) {
		try {
			Files.createDirectories(OUT_DIR);
			String fileName = "shot-" + LABEL + "-" + BACKEND + "-" + name + ".png";
			Screenshot.grab(OUT_DIR.toFile(), fileName, mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
		} catch (Throwable t) {
			LOGGER.warn("Screenshot failed for {}: {}", name, t.getMessage());
		}
	}

	private static void writeResults(Minecraft mc) {
		try {
			Files.createDirectories(OUT_DIR);
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("label", LABEL);
			root.put("requestedBackend", BACKEND.toUpperCase());
			try {
				root.put("deviceInfo", String.valueOf(RenderSystem.getDevice().getDeviceInfo()));
			} catch (Throwable ignored) {}
			List<String> mods = new ArrayList<>();
			FabricLoader.getInstance().getAllMods().forEach(m -> {
				String id = m.getMetadata().getId();
				if (!id.startsWith("fabric-") && !id.equals("java") && !id.equals("mixinextras")) {
					mods.add(id + "@" + m.getMetadata().getVersion().getFriendlyString());
				}
			});
			mods.sort(String::compareTo);
			root.put("mods", mods);
			root.put("scenes", SCENE_RESULTS);
			Path outFile = OUT_DIR.resolve("bench-" + LABEL + "-" + BACKEND + ".json");
			Files.writeString(outFile, GSON.toJson(root) + "\n");
			LOGGER.info("Wrote benchmark results to {}", outFile);
		} catch (Throwable t) {
			LOGGER.error("Failed to write benchmark results", t);
		}
	}

	private static void send(Minecraft mc, String cmd) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendCommand(cmd);
		}
	}

	private static void enter(Phase next, long now) {
		phase = next;
		phaseStartNs = now;
		phaseTicks = 0;
	}

	private static long currentRenderAllocBytes() {
		if (THREAD_MX != null && renderThreadId != -1L) {
			return Math.max(0L, THREAD_MX.getThreadAllocatedBytes(renderThreadId));
		}
		return 0L;
	}

	private static long totalGcCount() {
		long sum = 0L;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			long c = gc.getCollectionCount();
			if (c > 0) sum += c;
		}
		return sum;
	}

	private static long totalGcTimeMs() {
		long sum = 0L;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			long t = gc.getCollectionTime();
			if (t > 0) sum += t;
		}
		return sum;
	}

	private static double tailFps(long[] sorted, int n, double frac) {
		int k = Math.max(1, (int) Math.ceil(n * frac));
		long sum = 0L;
		for (int i = n - k; i < n; i++) sum += sorted[i];
		return sum > 0L ? (k * 1e9) / sum : 0.0;
	}

	private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
	private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
