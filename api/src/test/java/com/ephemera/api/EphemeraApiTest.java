package com.ephemera.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EphemeraApiTest {
	@Test
	void optionDescriptorAndSettingsBridgeContract() {
		EphemeraOptionDescriptor opt = new EphemeraOptionDescriptor(
			"terrainVulkanMeshShaders",
			"Vulkan Mesh Shaders",
			"Single-pass VK_EXT_mesh_shader terrain renderer",
			"Terrain",
			true,
			false
		);
		assertEquals("terrainVulkanMeshShaders", opt.key());
		assertTrue(opt.value());
		assertFalse(opt.requiresRestart());

		EphemeraSettingsBridge bridge = new EphemeraSettingsBridge() {
			private boolean enabled = true;
			@Override public List<EphemeraOptionDescriptor> options() { return List.of(opt); }
			@Override public boolean getBoolean(String key) { return "terrainVulkanMeshShaders".equals(key) && enabled; }
			@Override public boolean setBoolean(String key, boolean value) {
				if (!"terrainVulkanMeshShaders".equals(key)) return false;
				enabled = value;
				return true;
			}
			@Override public void save() {}
			@Override public String statusSummary() { return "Ephemera 0.1.0"; }
			@Override public boolean isVulkanMeshShaderActive() { return true; }
			@Override public String mixinHealthSummary() { return "ok"; }
		};

		EphemeraApi.register(new EphemeraApi.Service() {
			@Override public String version() { return "0.1.0"; }
			@Override public EphemeraCapabilities capabilities() { return null; }
			@Override public EphemeraVisibilityProvider visibility() { return null; }
			@Override public EphemeraShaderHooks shaders() { return null; }
			@Override public EphemeraSettingsBridge settings() { return bridge; }
		});

		assertTrue(EphemeraApi.isAvailable());
		EphemeraSettingsBridge resolved = EphemeraApi.get().orElseThrow().settings();
		assertNotNull(resolved);
		assertTrue(resolved.getBoolean("terrainVulkanMeshShaders"));
		assertTrue(resolved.setBoolean("terrainVulkanMeshShaders", false));
		assertFalse(resolved.getBoolean("terrainVulkanMeshShaders"));
		assertFalse(resolved.setBoolean("unknownKey", true));
	}
}
