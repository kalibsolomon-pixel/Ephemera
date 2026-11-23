package com.ephemera.ui;

import com.ephemera.platform.FullscreenWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * One "Fullscreen Mode" control over vanilla's Fullscreen and Exclusive Fullscreen options:
 * Windowed, Borderless (fullscreen without exclusive mode; Ephemera supplies the borderless
 * window on 26.2) and Exclusive.
 */
final class FullscreenModeBinding extends Binding<FullscreenModeBinding.Mode> {
	enum Mode {
		WINDOWED,
		BORDERLESS,
		EXCLUSIVE;

		Component label() {
			return Component.translatable("ephemera.fullscreen." + name().toLowerCase(Locale.ROOT));
		}
	}

	static final List<Mode> MODES = List.of(Mode.values());

	private final Minecraft minecraft;
	private final Options options;

	FullscreenModeBinding(Minecraft minecraft) {
		this.minecraft = minecraft;
		this.options = minecraft.options;
	}

	@Override
	String id() {
		return "fullscreenMode";
	}

	@Override
	Component label() {
		return Component.translatable("ephemera.option.fullscreenMode");
	}

	@Override
	Component description() {
		return Component.translatable("ephemera.tooltip.fullscreenMode." + get().name().toLowerCase(Locale.ROOT));
	}

	@Override
	Mode get() {
		if (!options.fullscreen().get()) {
			return Mode.WINDOWED;
		}
		return options.exclusiveFullscreen().get() ? Mode.EXCLUSIVE : Mode.BORDERLESS;
	}

	@Override
	void set(Mode mode) {
		if (mode == Mode.WINDOWED) {
			options.fullscreen().set(false);
			return;
		}
		boolean wasFullscreen = options.fullscreen().get();
		options.exclusiveFullscreen().set(mode == Mode.EXCLUSIVE);
		options.fullscreen().set(true);
		if (wasFullscreen && (Object) minecraft.getWindow() instanceof FullscreenWindow window) {
			window.ephemera$reapplyFullscreen();
		}
	}

	@Override
	Mode defaultValue() {
		return Mode.WINDOWED;
	}

	@Override
	Component format(Mode value) {
		return value.label();
	}

	@Override
	boolean isVanilla() {
		return true;
	}
}
