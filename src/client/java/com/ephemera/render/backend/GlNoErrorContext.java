package com.ephemera.render.backend;

import java.util.function.Supplier;

/** Carries the game window's debug options through the backend-independent creation helper. */
public final class GlNoErrorContext {
	private static final ScopedValue<Boolean> ALLOWED = ScopedValue.newInstance();
	private static volatile boolean active;

	private GlNoErrorContext() {}

	public static <T> T aroundWindowCreation(boolean allowed, Supplier<T> create) {
		return ScopedValue.where(ALLOWED, allowed).call(create::get);
	}

	public static boolean isAllowed() {
		return ALLOWED.orElse(false);
	}

	public static void markActive() {
		active = true;
	}

	public static void clearActive() {
		active = false;
	}

	public static boolean isActive() {
		return active;
	}
}
