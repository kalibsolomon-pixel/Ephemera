package com.ephemera.mixin;

import com.ephemera.core.config.EphemeraConfig;

/**
 * Client-side fail-soft mixin plugin with stand-down rules for third-party
 * performance/rendering mods when installed alongside Ephemera.
 */
public final class EphemeraClientMixinPlugin extends EphemeraCommonMixinPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		// Stand-down rules will be registered per subsystem as mixins land.
	}

	@Override
	protected boolean isConfigEnabled(String simpleName, EphemeraConfig cfg) {
		return true;
	}
}
