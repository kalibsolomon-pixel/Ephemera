package com.ephemera.mixin.client.ui;

import com.ephemera.EphemeraCommon;
import com.ephemera.platform.BorderlessFlipWindow;
import com.ephemera.platform.Win32DisplayRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 sizes its borderless fullscreen window one pixel wider than the monitor and hides the extra
 * column from the framebuffer, so drivers never promote it to exclusive fullscreen. That window
 * can never get Independent Flip either: DWM composites every frame and spills 1 px onto adjacent
 * monitors. With Borderless Flip Optimization on, the window matches the monitor exactly, strips
 * lingering Win32 frame/border styles & Win11 rounded corners, and lets Vulkan disallow implicit
 * FSE cleanly via {@code VK_EXT_full_screen_exclusive}.
 *
 * <p>Tracks manual exact/legacy borderless, SDL-managed desktop borderless, and exclusive
 * fullscreen as distinct applied outcomes, and only commits geometry once SDL reports success.
 */
@Mixin(Window.class)
public abstract class WindowBorderlessFlipMixin implements BorderlessFlipWindow {
	@Shadow private boolean fullscreen;
	@Shadow private boolean borderlessFullscreen;
	@Shadow private boolean exclusiveFullscreen;

	@Shadow public abstract long handle();
	@Shadow private void setMode() { throw new AssertionError(); }

	@Unique private boolean ephemera$flip;
	@Unique private boolean ephemera$rectApplied;
	@Unique private boolean ephemera$sdlBorderless;
	@Unique private int ephemera$pendingX;
	@Unique private int ephemera$pendingY;
	@Unique private int ephemera$pendingW;
	@Unique private int ephemera$pendingH;

	@Inject(method = "applyBorderlessFullscreenWindow", at = @At("HEAD"))
	private void ephemera$chooseFlip(CallbackInfoReturnable<Boolean> cir) {
		ephemera$flip = EphemeraCommon.config().borderlessFlipOptimization;
		ephemera$rectApplied = false;
		ephemera$sdlBorderless = false;
		ephemera$pendingX = 0;
		ephemera$pendingY = 0;
		ephemera$pendingW = 0;
		ephemera$pendingH = 0;
	}

	@WrapOperation(
		method = "applyBorderlessFullscreenWindow",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setWindowSizeAndPosition(IIII)Z")
	)
	private boolean ephemera$exactBorderlessRect(Window self, int x, int y, int width, int height,
												 Operation<Boolean> original) {
		int exactWidth = ephemera$flip ? width - 1 : width;
		boolean ok = original.call(self, x, y, exactWidth, height);
		if (ok) {
			ephemera$rectApplied = true;
			ephemera$pendingX = x;
			ephemera$pendingY = y;
			ephemera$pendingW = exactWidth;
			ephemera$pendingH = height;
		}
		return ok;
	}

	@Inject(method = "applyBorderlessFullscreenWindow", at = @At("RETURN"))
	private void ephemera$noteBorderless(CallbackInfoReturnable<Boolean> cir) {
		boolean ok = cir.getReturnValueZ();
		if (ok && borderlessFullscreen && ephemera$rectApplied) {
			ephemera$sdlBorderless = false;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), true, ephemera$flip,
				ephemera$pendingX, ephemera$pendingY, ephemera$pendingW, ephemera$pendingH);
		} else if (ok && !borderlessFullscreen && !exclusiveFullscreen) {
			// Vanilla fell back to SDL-managed desktop borderless fullscreen (not exclusive).
			ephemera$sdlBorderless = true;
			ephemera$flip = false;
			ephemera$rectApplied = false;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), true, false);
		} else {
			ephemera$sdlBorderless = false;
			ephemera$flip = false;
			ephemera$rectApplied = false;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), false, false);
		}
		ephemera$invalidateSurfaceIfReady();
	}

	@Inject(method = "leaveBorderlessFullscreenWindow", at = @At("HEAD"))
	private void ephemera$noteLeave(CallbackInfo ci) {
		if (borderlessFullscreen) {
			ephemera$flip = false;
			ephemera$rectApplied = false;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), false, false);
			ephemera$invalidateSurfaceIfReady();
		}
	}

	@Inject(method = "setMode", at = @At("RETURN"))
	private void ephemera$syncAppliedFullscreenState(CallbackInfo ci) {
		if (!fullscreen) {
			boolean wasBorderless = Win32DisplayRuntime.isBorderlessActive();
			ephemera$sdlBorderless = false;
			ephemera$flip = false;
			ephemera$rectApplied = false;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), false, false);
			if (wasBorderless) {
				ephemera$invalidateSurfaceIfReady();
			}
			return;
		}
		if (!borderlessFullscreen) {
			ephemera$flip = false;
			ephemera$rectApplied = false;
			ephemera$sdlBorderless = !exclusiveFullscreen;
			Win32DisplayRuntime.onBorderlessChanged(ephemera$hwnd(), ephemera$sdlBorderless, false);
		}
	}

	@Inject(method = "setExclusiveFullscreen", at = @At("RETURN"))
	private void ephemera$reconfigureOnExclusiveChange(boolean exclusive, CallbackInfo ci) {
		ephemera$invalidateSurfaceIfReady();
	}

	@Inject(method = "framebufferWidthPadding", at = @At("HEAD"), cancellable = true)
	private void ephemera$noPadding(CallbackInfoReturnable<Integer> cir) {
		if (borderlessFullscreen && ephemera$flip && ephemera$rectApplied) {
			cir.setReturnValue(0);
		}
	}

	@Override
	public void ephemera$reapplyBorderlessFlip() {
		if (borderlessFullscreen) {
			setMode();
			ephemera$invalidateSurfaceIfReady();
		}
	}

	@Override
	public boolean ephemera$isBorderlessFullscreen() {
		return borderlessFullscreen || (fullscreen && ephemera$sdlBorderless);
	}

	@Override
	public boolean ephemera$isExclusiveFullscreen() {
		return fullscreen && exclusiveFullscreen && !borderlessFullscreen && !ephemera$sdlBorderless;
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
		if (!Win32DisplayRuntime.IS_WINDOWS) {
			return 0L;
		}
		int props = SDLVideo.SDL_GetWindowProperties(handle());
		return props == 0 ? 0L : SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
	}
}
