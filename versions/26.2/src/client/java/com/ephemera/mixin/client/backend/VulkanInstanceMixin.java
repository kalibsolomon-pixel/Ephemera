package com.ephemera.mixin.client.backend;

import com.ephemera.platform.Win32DisplayRuntime;
import com.ephemera.render.backend.VulkanModernCapabilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanDebug;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import java.util.Set;
import org.lwjgl.vulkan.KHRGetSurfaceCapabilities2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Enables {@code VK_KHR_get_surface_capabilities2} on Windows when supported by the Vulkan loader,
 * unlocking {@code VK_EXT_full_screen_exclusive} so exact-bounds borderless swapchains can
 * explicitly disallow legacy Fullscreen Exclusive promotion while staying on Windows DWM
 * Hardware Independent Flip.
 */
@Mixin(VulkanInstance.class)
abstract class VulkanInstanceMixin {
	@WrapOperation(
		method = "<init>(IZZ)V",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;"
		)
	)
	private VulkanDebug ephemera$enableSurfaceCapabilities2(int debugVerbosity, boolean useLabels,
															Set<String> supportedExtensions,
															Set<String> enabledExtensions,
															Operation<VulkanDebug> original) {
		if (Win32DisplayRuntime.IS_WINDOWS
			&& supportedExtensions.contains(KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME)) {
			enabledExtensions.add(KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
			VulkanModernCapabilities.noteSurfaceCapabilities2Enabled();
		}
		return original.call(debugVerbosity, useLabels, supportedExtensions, enabledExtensions);
	}
}
