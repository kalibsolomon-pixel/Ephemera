package com.ephemera.platform;

import com.ephemera.mixin.client.culling.EntityRendererAccessor;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Minecraft 26.3 side of the version seam. 26.3 moved the GPU layer to
 * {@code com.mojang.renderpearl} and the window to SDL; the Ephemera GL/Vulkan backend,
 * terrain engine and fast-write paths are not ported yet, so those hooks report inactive.
 */
public final class RenderPlatform {
	private RenderPlatform() {}

	public static void onWorldUnload() {}

	public static void onResourceReloadComplete() {}

	public static void refreshBlockAtlasAnisotropy() {}

	public static boolean isVulkanMeshShaderActive() {
		return false;
	}

	public static String meshShaderLabel() {
		return "none";
	}

	public static boolean isVulkanAsyncPresentActive() {
		return false;
	}

	/** Backend name of the live device, or null before the device exists. */
	public static String deviceBackendName() {
		GpuDevice device = RenderSystem.tryGetDevice();
		return device != null ? device.getDeviceInfo().backendName() : null;
	}

	public static String decorateBackendName(String backend) {
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

	/** Aurora {@code ephemera:fast_write} handles are not published on 26.3 yet. */
	public static Map<String, MethodHandle> fastWriteHandles() {
		return Map.of();
	}

	/** The renderer's culling box for {@code entity} at its current position. */
	public static AABB cullingBox(EntityRenderer<?, ?> renderer, Entity entity) {
		// Partial tick 1 is the current box, matching 26.2 (callers add the xOld motion themselves).
		return ((EntityRendererAccessor) renderer).ephemera$cullingBox(entity, 1.0f);
	}
}
