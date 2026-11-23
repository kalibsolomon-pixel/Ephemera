package com.ephemera.mixin.client.ui;

import com.ephemera.EphemeraCommon;
import com.ephemera.platform.BorderlessFlipWindow;
import com.ephemera.platform.FullscreenWindow;
import com.ephemera.platform.Win32DisplayRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimized borderless fullscreen for Minecraft 26.2.
 *
 * <p>With Borderless Flip Optimization on, the window matches the monitor exactly (no +1 px
 * overhang), avoids exclusive-fullscreen startup flashes, strips lingering Win32 frame/border
 * styles and Windows 11 rounded corners, and invalidates the GPU surface on mode changes so
 * Vulkan updates {@code VK_EXT_full_screen_exclusive} and swapchain headroom live.
 */
@Mixin(Window.class)
public abstract class WindowBorderlessMixin implements FullscreenWindow, BorderlessFlipWindow {
	@Shadow @Final private long handle;
	@Shadow @Final private MonitorManager monitorManager;
	@Shadow @Final private boolean exclusiveFullscreen;
	@Shadow private boolean fullscreen;
	@Shadow private int x;
	@Shadow private int y;
	@Shadow private int width;
	@Shadow private int height;
	@Shadow private int windowedX;
	@Shadow private int windowedY;
	@Shadow private int windowedWidth;
	@Shadow private int windowedHeight;

	@Shadow private void setMode() { throw new AssertionError(); }
	@Shadow private boolean isSoftScreen() { throw new AssertionError(); }
	@Shadow private void refreshFramebufferSize() { throw new AssertionError(); }

	@Unique
	private boolean ephemera$borderless;
	@Unique
	private boolean ephemera$flip;

	/** Avoid entering exclusive fullscreen at startup when launching directly into borderless. */
	@WrapOperation(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/platform/Window;createWindow(Lcom/mojang/blaze3d/systems/GpuBackend;IILjava/lang/String;J)J"
		)
	)
	private long ephemera$avoidExclusiveFlashOnStartup(Window self, GpuBackend backend, int w, int h,
													   String title, long monitor, Operation<Long> original) {
		if (fullscreen && ephemera$wantsBorderless()) {
			return original.call(self, backend, w, h, title, 0L);
		}
		return original.call(self, backend, w, h, title, monitor);
	}

	/**
	 * Derive framebuffer row padding strictly from the live applied window mode rather than
	 * vanilla's startup-only {@code exclusiveFullscreen} field: only legacy Windows borderless
	 * (with flip optimization off) adds the 1 px overhang that must be subtracted here.
	 */
	@Inject(method = "isSoftScreen", at = @At("HEAD"), cancellable = true)
	private void ephemera$liveSoftScreenMode(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(ephemera$isLegacyWindowsBorderless());
	}

	@Unique
	private boolean ephemera$isLegacyWindowsBorderless() {
		return Win32DisplayRuntime.IS_WINDOWS && fullscreen && ephemera$borderless && !ephemera$flip;
	}

	@Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
	private void ephemera$borderlessMode(CallbackInfo ci) {
		if (fullscreen && ephemera$wantsBorderless()) {
			Monitor monitor = monitorManager.findBestMonitor((Window) (Object) this);
			if (monitor == null) {
				return;
			}
			if (!ephemera$borderless && GLFW.glfwGetWindowMonitor(handle) == 0L) {
				windowedX = x;
				windowedY = y;
				windowedWidth = width;
				windowedHeight = height;
			}
			if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
				GLFW.glfwRestoreWindow(handle);
			}
			VideoMode mode = monitor.currentMode();
			ephemera$borderless = true;
			ephemera$flip = EphemeraCommon.config().borderlessFlipOptimization;
			int extra = ephemera$isLegacyWindowsBorderless() ? 1 : 0;
			int targetX = monitor.x();
			int targetY = monitor.y();
			int targetW = mode.getWidth();
			int targetH = mode.getHeight() + extra;

			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowMonitor(handle, 0L, targetX, targetY, targetW, targetH, GLFW.GLFW_DONT_CARE);
			x = targetX;
			y = targetY;
			width = targetW;
			height = targetH;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), true, ephemera$flip, targetX, targetY, targetW, targetH);
			refreshFramebufferSize();
			ephemera$invalidateSurfaceIfReady();
			ci.cancel();
		} else if (ephemera$borderless) {
			ephemera$borderless = false;
			ephemera$flip = false;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), false, false);
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_TRUE);
			// Vanilla saves the current bounds as the windowed ones when entering exclusive mode.
			x = windowedX;
			y = windowedY;
			width = windowedWidth;
			height = windowedHeight;
			ephemera$invalidateSurfaceIfReady();
		}
	}

	@Override
	public void ephemera$reapplyFullscreen() {
		if (fullscreen) {
			setMode();
		}
	}

	@Override
	public void ephemera$reapplyBorderlessFlip() {
		if (ephemera$borderless) {
			setMode();
		}
	}

	@Override
	public boolean ephemera$isBorderlessFullscreen() {
		return ephemera$borderless;
	}

	@Override
	public boolean ephemera$isExclusiveFullscreen() {
		return fullscreen && !ephemera$borderless;
	}

	@Unique
	private static void ephemera$invalidateSurfaceIfReady() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.windowSurface() != null) {
			mc.invalidateSurfaceConfiguration();
		}
	}

	@Unique
	private long ephemera$hwnd() {
		return Win32DisplayRuntime.IS_WINDOWS ? GLFWNativeWin32.glfwGetWin32Window(handle) : 0L;
	}

	/** Exclusive Fullscreen off means borderless; read live so switching needs no restart. */
	@Unique
	private boolean ephemera$wantsBorderless() {
		if (MacosUtil.IS_MACOS) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.options != null) {
			return !minecraft.options.exclusiveFullscreen().get();
		}
		return !exclusiveFullscreen;
	}
}
