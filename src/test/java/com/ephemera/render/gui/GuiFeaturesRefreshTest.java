package com.ephemera.render.gui;

import com.ephemera.core.config.EphemeraConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GuiFeaturesRefreshTest {
	@Test
	void initRefreshesUnfocusedFpsAndGuiSnapshotOnEditAndUndo() {
		EphemeraConfig c = new EphemeraConfig();
		c.unfocusedFpsLimitEnabled = false;
		c.unfocusedFpsLimit = 30;
		c.guiGlintFps = 60;
		c.guiFastLtrText = true;
		GuiFeatures.init(c);

		assertFalse(GuiFeatures.get().unfocusedFpsLimit);
		assertEquals(30, GuiFeatures.get().unfocusedFps);

		// Simulate live edit in EphemeraSettingsScreen
		c.unfocusedFpsLimitEnabled = true;
		c.unfocusedFpsLimit = 15;
		c.guiGlintFps = 30;
		c.guiFastLtrText = false;
		GuiFeatures.init(c);

		GuiFeatures updated = GuiFeatures.get();
		assertTrue(updated.unfocusedFpsLimit);
		assertEquals(15, updated.unfocusedFps);
		assertEquals(1_000_000_000L / 30, updated.glintPeriodNanos);
		assertFalse(updated.fastLtrText);

		// Simulate Undo restoring original config + GuiFeatures.init()
		c.unfocusedFpsLimitEnabled = false;
		c.unfocusedFpsLimit = 30;
		c.guiGlintFps = 60;
		c.guiFastLtrText = true;
		GuiFeatures.init(c);

		GuiFeatures reverted = GuiFeatures.get();
		assertFalse(reverted.unfocusedFpsLimit);
		assertEquals(30, reverted.unfocusedFps);
		assertTrue(reverted.fastLtrText);
	}
}
