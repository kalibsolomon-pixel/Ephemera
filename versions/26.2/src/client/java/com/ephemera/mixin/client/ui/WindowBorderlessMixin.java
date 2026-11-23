package com.ephemera.mixin.client.ui;

import com.ephemera.platform.FullscreenWindow;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Borderless fullscreen for 26.2. With Exclusive Fullscreen off, vanilla still attaches the window
 * to the monitor (a "soft" exclusive mode that minimizes on alt-tab and may switch display modes).
 * This instead turns the window into an undecorated window covering the monitor at its current
 * mode, which is what 26.3 does natively. Exclusive Fullscreen keeps the vanilla path.
 */
@Mixin(Window.class)
public abstract class WindowBorderlessMixin implements FullscreenWindow {
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

	@Unique
	private boolean ephemera$borderless;

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
			VideoMode mode = monitor.currentMode();
			// The soft-screen framebuffer drops one row; a window one pixel taller keeps it at the
			// monitor height and stops drivers from promoting the window to exclusive fullscreen.
			int extra = isSoftScreen() ? 1 : 0;
			ephemera$borderless = true;
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowMonitor(handle, 0L, monitor.x(), monitor.y(), mode.getWidth(), mode.getHeight() + extra, GLFW.GLFW_DONT_CARE);
			ci.cancel();
		} else if (ephemera$borderless) {
			ephemera$borderless = false;
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			// Vanilla saves the current bounds as the windowed ones when entering exclusive mode.
			x = windowedX;
			y = windowedY;
			width = windowedWidth;
			height = windowedHeight;
		}
	}

	@Override
	public void ephemera$reapplyFullscreen() {
		if (fullscreen) {
			setMode();
		}
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
