package com.ephemera.core.pacing;

import com.ephemera.core.config.EphemeraConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CapturePacingPolicyTest {
	@Test
	void resolvesCaptureFpsCapRespectingUserCap() {
		assertEquals(0, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.OFF, 240, 0));
		assertEquals(165, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.OFF, 240, 165));

		assertEquals(240, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.REFRESH_RATE, 240, 0));
		assertEquals(144, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.REFRESH_RATE, 144, 0));
		assertEquals(60, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.REFRESH_RATE, 0, 0));

		// Never raises a stricter user cap
		assertEquals(120, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.REFRESH_RATE, 240, 120));
		assertEquals(120, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.FPS_180, 240, 120));
		assertEquals(60, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.FPS_60, 240, 120));
		assertEquals(180, CapturePacingPolicy.resolveCaptureFpsCap(EphemeraConfig.CaptureFpsCapMode.FPS_180, 240, 0));
	}

	@Test
	void reducesSpinTailDuringCapture() {
		assertEquals(500, CapturePacingPolicy.effectiveSpinTailMicros(500, false, true));
		assertEquals(300, CapturePacingPolicy.effectiveSpinTailMicros(500, true, true));
		assertEquals(450, CapturePacingPolicy.effectiveSpinTailMicros(500, true, false));
		assertEquals(100, CapturePacingPolicy.effectiveSpinTailMicros(100, true, true));
	}

	@Test
	void tightensUploadBudgetDuringCaptureWithFloorAndCeiling() {
		assertEquals(4_000_000L, CapturePacingPolicy.effectiveUploadBudgetNs(4_000_000L, false));
		assertEquals(1_500_000L, CapturePacingPolicy.effectiveUploadBudgetNs(4_000_000L, true));
		assertEquals(650_000L, CapturePacingPolicy.effectiveUploadBudgetNs(1_000_000L, true));
		assertEquals(500_000L, CapturePacingPolicy.effectiveUploadBudgetNs(600_000L, true));
	}

	@Test
	void expandsSwapchainImagesOnlyWhenUncappedBorderlessOrCapture() {
		// Uncapped borderless -> 4 images
		assertEquals(4, CapturePacingPolicy.preferredVulkanSwapchainImages(2, 8, 3, true, true, false));
		// Uncapped capture in windowed/exclusive -> 4 images
		assertEquals(4, CapturePacingPolicy.preferredVulkanSwapchainImages(2, 8, 3, true, false, true));
		// VSync (FIFO) -> stays at 3 images to avoid latency
		assertEquals(3, CapturePacingPolicy.preferredVulkanSwapchainImages(2, 8, 3, false, true, true));
		// Respects surface maxImageCount clamp
		assertEquals(3, CapturePacingPolicy.preferredVulkanSwapchainImages(2, 3, 3, true, true, true));
		// Unlimited maxImageCount (0 per Vulkan spec) -> 4 images
		assertEquals(4, CapturePacingPolicy.preferredVulkanSwapchainImages(2, 0, 3, true, true, false));
	}
}
