package com.ephemera.api;

import java.util.List;

/**
 * Live settings and engine-status bridge allowing companion UIs (such as Aurora
 * Client's Settings -> Performance page) to inspect and toggle Ephemera options
 * without depending on Ephemera internals.
 */
public interface EphemeraSettingsBridge {
	List<EphemeraOptionDescriptor> options();

	boolean getBoolean(String key);

	boolean setBoolean(String key, boolean value);

	void save();

	String statusSummary();

	boolean isVulkanMeshShaderActive();

	String mixinHealthSummary();
}
