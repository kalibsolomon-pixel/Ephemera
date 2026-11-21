package com.ephemera.mixin.client.backend;

import com.ephemera.render.backend.GlNoErrorContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.GpuBackend;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class MinecraftGlDebugOptionsMixin {
	@Shadow @Final public Options options;

	@WrapOperation(method = "<init>", at = @At(value = "NEW",
		target = "(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/blaze3d/systems/GpuBackend;)Lcom/mojang/blaze3d/platform/Window;"))
	private Window ephemera$passDebugOptions(WindowEventHandler events, DisplayData display, String fullscreenMode,
			boolean exclusive, String title, MonitorManager monitors, GpuBackend backend, Operation<Window> original,
			@Local(argsOnly = true) GameConfig config) {
		boolean allowed = options.glDebugVerbosity <= 0 && !SharedConstants.DEBUG_SYNCHRONOUS_GL_LOGS
			&& !config.game.renderDebugLabels;
		return GlNoErrorContext.aroundWindowCreation(allowed,
			() -> original.call(events, display, fullscreenMode, exclusive, title, monitors, backend));
	}
}
