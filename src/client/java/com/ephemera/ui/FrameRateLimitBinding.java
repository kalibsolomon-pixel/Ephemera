package com.ephemera.ui;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.pacing.FrameRateLimitPolicy;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Extended Max Framerate slider for Ephemera's Video Settings screen:
 * {@code 10..250} in steps of 10 (synced to vanilla {@code options.txt}),
 * {@code 260..300} in steps of 10, {@code 400..1000} in steps of 100, and {@code Unlimited}.
 */
final class FrameRateLimitBinding extends Binding<Integer> {
	private static final SettingRow.SliderModel<Integer> SLIDER_MODEL = new SettingRow.SliderModel<>() {
		@Override
		public double toFraction(Integer value) {
			return FrameRateLimitPolicy.toSliderFraction(value == null ? FrameRateLimitPolicy.UNLIMITED : value);
		}

		@Override
		public Integer fromFraction(double fraction) {
			return FrameRateLimitPolicy.fromSliderFraction(fraction);
		}

		@Override
		public int stops() {
			return 0;
		}

		@Override
		public Integer step(Integer value, int dir) {
			return FrameRateLimitPolicy.step(value == null ? FrameRateLimitPolicy.UNLIMITED : value, dir);
		}
	};

	private final Options options;

	private FrameRateLimitBinding(Options options) {
		this.options = options;
	}

	static SettingRow.Slider<Integer> row(Options options) {
		return new SettingRow.Slider<>(new FrameRateLimitBinding(options), SLIDER_MODEL, false);
	}

	@Override
	String id() {
		return "extendedFpsLimit";
	}

	@Override
	Component label() {
		return Component.translatable("options.framerateLimit");
	}

	@Override
	Component description() {
		return Component.translatable("ephemera.tooltip.extendedFpsLimit");
	}

	@Override
	Integer get() {
		int vanilla = options.framerateLimit().get();
		if (vanilla < FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF) {
			return vanilla;
		}
		return FrameRateLimitPolicy.sanitizeExtended(EphemeraCommon.config().extendedFpsLimit);
	}

	@Override
	void set(Integer value) {
		int target = value == null ? FrameRateLimitPolicy.UNLIMITED : value;
		EphemeraConfig cfg = EphemeraCommon.config();
		if (target == FrameRateLimitPolicy.UNLIMITED) {
			cfg.extendedFpsLimit = FrameRateLimitPolicy.UNLIMITED;
			options.framerateLimit().set(FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF);
		} else if (target < FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF) {
			cfg.extendedFpsLimit = FrameRateLimitPolicy.UNLIMITED;
			options.framerateLimit().set(target);
		} else {
			cfg.extendedFpsLimit = FrameRateLimitPolicy.sanitizeExtended(target);
			options.framerateLimit().set(FrameRateLimitPolicy.VANILLA_UNLIMITED_CUTOFF);
		}
	}

	@Override
	Integer defaultValue() {
		return FrameRateLimitPolicy.DEFAULT_FPS;
	}

	@Override
	Component format(Integer value) {
		if (value == null || value <= FrameRateLimitPolicy.UNLIMITED) {
			return Component.translatable("options.framerateLimit.max");
		}
		return Component.translatable("options.framerate", value);
	}
}
