package com.ephemera.platform;

import com.ephemera.EphemeraClient;
import com.ephemera.mixin.client.culling.EntityRendererAccessor;
import com.ephemera.render.backend.VulkanAsyncPresenter;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.ephemera.render.fastwrite.FastGlyphWriter;
import com.ephemera.render.fastwrite.FastLineWriter;
import com.ephemera.render.gui.TextureSetupCache;
import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
import com.ephemera.visuals.AnisotropyService;
import com.ephemera.visuals.ShaderPatchService;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Minecraft 26.3 side of the version seam (RenderPearl GPU layer, SDL3 window, native
 * {@code MULTIDRAW_TERRAIN}, and full Ephemera terrain + Vulkan Task+Mesh engine).
 */
public final class RenderPlatform {
	private RenderPlatform() {}

	/** Leaving a world: drop GPU-side caches tied to its render data. */
	public static void onWorldUnload() {
		TextureSetupCache.clear();
		ChunkRenderBatcher.get().release();
	}

	/** Resource reload finished: textures and samplers were recreated. */
	public static void onResourceReloadComplete() {
		TextureSetupCache.clear();
		AnisotropyService.clearCache();
		ShaderPatchService.clearTextCache();
		VulkanMeshTerrainEngine.get().prewarm();
	}

	public static void refreshBlockAtlasAnisotropy() {
		AnisotropyService.refreshBlockAtlas();
	}

	public static boolean isVulkanMeshShaderActive() {
		try {
			return VulkanMeshTerrainEngine.get().isAvailable();
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static String meshShaderLabel() {
		return VulkanMeshTerrainEngine.get().usesTaskShader() ? "VK_EXT_mesh_shader (Task+Mesh)" : "VK_EXT_mesh_shader";
	}

	public static boolean isVulkanAsyncPresentActive() {
		return VulkanAsyncPresenter.isActive();
	}

	/** Backend name of the live device, or null before the device exists. */
	public static String deviceBackendName() {
		GpuDevice device = RenderSystem.tryGetDevice();
		return device != null ? device.getDeviceInfo().backendName() : null;
	}

	/** Adds the active terrain engine to a backend name for diagnostics. */
	public static String decorateBackendName(String backend) {
		if (VulkanModernCapabilities.isMeshEngineActive() && "Vulkan".equals(backend)) {
			return VulkanModernCapabilities.get().taskShaderEnabled() ? "Vulkan • Task+Mesh" : "Vulkan • Mesh";
		}
		return backend;
	}

	/** Refresh rate of the monitor the window lives on, rounded; 0 when unknown. */
	public static int refreshRate(Minecraft mc) {
		Window win = mc.getWindow();
		if (win == null) {
			return 0;
		}
		Monitor monitor = win.findBestMonitor();
		return monitor != null ? Math.round(monitor.currentMode().getRefreshRate()) : 0;
	}

	/** Aurora {@code ephemera:fast_write} ObjectShare handles; empty when unavailable on this version. */
	public static Map<String, MethodHandle> fastWriteHandles() {
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodHandle sdfGlyph = lookup.findStatic(FastGlyphWriter.class, "writeSdfGlyph",
				MethodType.methodType(boolean.class,
					VertexConsumer.class, Matrix4fc.class, int.class,
					float.class, float.class, float.class, float.class, float.class, float.class,
					float.class, float.class, int.class, int.class, boolean.class, boolean.class,
					float.class, float.class, float.class, float.class));
			MethodHandle sdfBox = lookup.findStatic(FastLineWriter.class, "writeSdfBox",
				MethodType.methodType(boolean.class,
					VertexConsumer.class, Matrix4f.class, Vec3.class,
					double.class, double.class, double.class, double.class, double.class, double.class,
					float.class, float.class, float.class, float.class, float.class));
			MethodHandle line = lookup.findStatic(FastLineWriter.class, "writeLine",
				MethodType.methodType(boolean.class,
					VertexConsumer.class, Matrix4f.class, Vec3.class,
					double.class, double.class, double.class, double.class, double.class, double.class,
					float.class, float.class, float.class, float.class, float.class, boolean.class));
			return Map.of("sdfGlyph", sdfGlyph, "sdfBox", sdfBox, "line", line);
		} catch (Throwable t) {
			EphemeraClient.LOGGER.warn("Could not publish Ephemera fast_write ObjectShare handles: {}", t.toString());
			return Map.of();
		}
	}

	/** The renderer's culling box for {@code entity} at its current position. */
	public static AABB cullingBox(EntityRenderer<?, ?> renderer, Entity entity) {
		// Partial tick 1 is the current box, matching 26.2 (callers add the xOld motion themselves).
		return ((EntityRendererAccessor) renderer).ephemera$cullingBox(entity, 1.0f);
	}

	/** Standard (non-multidraw) terrain pipeline for {@code layer}. */
	public static RenderPipeline terrainPipeline(ChunkSectionLayer layer) {
		return layer.pipeline(false);
	}

	/** True on 26.3: {@code Globals} UBO packs {@code GlintAlpha}/{@code GameTime} into the vec3 tail pads. */
	public static boolean packedGlobalsUbo() {
		return true;
	}
}
