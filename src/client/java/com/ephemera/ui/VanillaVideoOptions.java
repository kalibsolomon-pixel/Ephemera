package com.ephemera.ui;

import com.ephemera.EphemeraClient;
import com.ephemera.platform.FullscreenWindow;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every option of vanilla Video Settings, grouped into the Display and Quality pages. Also owns the
 * screen-local "Fullscreen Resolution" option (vanilla builds it inside the screen) and the
 * open-time values used for Undo and for deciding whether textures need a reload on close.
 */
final class VanillaVideoOptions {
	final Options options;
	private final Minecraft minecraft;
	private final OptionInstance<Integer> fullscreenResolution;
	private final OptionInstance<?> macMenuVisibility;
	private final Map<OptionInstance<?>, Object> snapshot = new LinkedHashMap<>();

	VanillaVideoOptions(Minecraft minecraft) {
		this.minecraft = minecraft;
		this.options = minecraft.options;
		this.fullscreenResolution = createFullscreenResolution(minecraft.getWindow());
		this.macMenuVisibility = MacosUtil.IS_MACOS ? optionalOption(options, "macFullscreenMenuVisibility") : null;
		for (OptionInstance<?> option : all()) {
			snapshot.put(option, option.get());
		}
	}

	// --- pages ---

	void display(List<SettingRow> r) {
		r.add(new SettingRow.Header("ephemera.section.window"));
		r.add(new SettingRow.Choice<>(new FullscreenModeBinding(minecraft), FullscreenModeBinding.MODES));
		SettingRow resolution = row(fullscreenResolution);
		if (resolution != null) {
			// The video mode is only used by exclusive fullscreen; borderless keeps the desktop mode.
			resolution.enabledWhen(() -> options.exclusiveFullscreen().get());
			r.add(resolution);
		}
		if (macMenuVisibility != null) {
			add(r, macMenuVisibility);
		}
		add(r, options.preferredGraphicsBackend());
		r.add(new SettingRow.Header("ephemera.section.frameRate"));
		r.add(FrameRateLimitBinding.row(options));
		add(r, options.enableVsync());
		add(r, options.inactivityFpsLimit());
		r.add(new SettingRow.Header("ephemera.section.interface"));
		add(r, options.guiScale());
		add(r, options.gamma());
		add(r, options.menuBackgroundBlurriness());
		add(r, options.attackIndicator());
		add(r, options.showAutosaveIndicator());
		add(r, options.vignette());
	}

	void quality(List<SettingRow> r) {
		r.add(new SettingRow.Header("ephemera.section.preset"));
		add(r, options.graphicsPreset());
		r.add(new SettingRow.Header("ephemera.section.world"));
		add(r, options.renderDistance());
		add(r, options.simulationDistance());
		add(r, options.entityDistanceScaling());
		add(r, options.prioritizeChunkUpdates());
		add(r, options.chunkSectionFadeInTime());
		add(r, options.biomeBlendRadius());
		r.add(new SettingRow.Header("ephemera.section.effects"));
		add(r, options.ambientOcclusion());
		add(r, options.cloudStatus());
		add(r, options.cloudRange());
		add(r, options.weatherRadius());
		add(r, options.particles());
		add(r, options.entityShadows());
		add(r, options.cutoutLeaves());
		add(r, options.improvedTransparency());
		r.add(new SettingRow.Header("ephemera.section.textures"));
		add(r, options.mipmapLevels());
		add(r, options.textureFiltering());
		SettingRow anisotropy = row(options.maxAnisotropyBit());
		if (anisotropy != null) {
			anisotropy.enabledWhen(() -> options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC);
			r.add(anisotropy);
		}
	}

	// --- lifecycle ---

	/** Options on both pages, preset first so restoring it does not overwrite the others. */
	List<OptionInstance<?>> all() {
		List<OptionInstance<?>> all = new ArrayList<>(List.of(
			options.graphicsPreset(),
			options.fullscreen(), options.exclusiveFullscreen(), fullscreenResolution, options.preferredGraphicsBackend(),
			options.framerateLimit(), options.enableVsync(), options.inactivityFpsLimit(),
			options.guiScale(), options.gamma(), options.menuBackgroundBlurriness(), options.attackIndicator(),
			options.showAutosaveIndicator(), options.vignette(),
			options.renderDistance(), options.simulationDistance(), options.entityDistanceScaling(),
			options.prioritizeChunkUpdates(), options.chunkSectionFadeInTime(), options.biomeBlendRadius(),
			options.ambientOcclusion(), options.cloudStatus(), options.cloudRange(), options.weatherRadius(),
			options.particles(), options.entityShadows(), options.cutoutLeaves(), options.improvedTransparency(),
			options.mipmapLevels(), options.textureFiltering(), options.maxAnisotropyBit()));
		if (macMenuVisibility != null) {
			all.add(macMenuVisibility);
		}
		return all;
	}

	boolean changedSinceOpen() {
		for (Map.Entry<OptionInstance<?>, Object> e : snapshot.entrySet()) {
			if (!e.getKey().get().equals(e.getValue())) {
				return true;
			}
		}
		return false;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	void revert() {
		boolean wasFullscreen = options.fullscreen().get();
		boolean wasExclusive = options.exclusiveFullscreen().get();
		for (Map.Entry<OptionInstance<?>, Object> e : snapshot.entrySet()) {
			OptionInstance option = e.getKey();
			if (!option.get().equals(e.getValue())) {
				new Binding.Vanilla<>(option).set(e.getValue());
			}
		}
		// On 26.2, exclusiveFullscreen has a no-op listener and fullscreen only toggles the native
		// window when its boolean changes; reapply once if fullscreen stayed true across a mode flip.
		if (wasFullscreen
			&& options.fullscreen().get()
			&& wasExclusive != options.exclusiveFullscreen().get()
			&& (Object) minecraft.getWindow() instanceof FullscreenWindow window) {
			window.ephemera$reapplyFullscreen();
		}
	}

	/** Vanilla's close-time work: apply the fullscreen mode, persist, and reload textures if needed. */
	void apply() {
		minecraft.getWindow().changeFullscreenVideoMode();
		options.save();
		if (!snapshot.get(options.mipmapLevels()).equals(options.mipmapLevels().get())
			|| !snapshot.get(options.maxAnisotropyBit()).equals(options.maxAnisotropyBit().get())
			|| snapshot.get(options.textureFiltering()) != options.textureFiltering().get()) {
			minecraft.updateMaxMipLevel(options.mipmapLevels().get());
			minecraft.delayTextureReload();
		}
		for (OptionInstance<?> option : all()) {
			snapshot.put(option, option.get());
		}
	}

	// --- rows ---

	private void add(List<SettingRow> rows, OptionInstance<?> option) {
		SettingRow row = row(option);
		if (row != null) {
			rows.add(row);
		}
	}

	/**
	 * Picks the control for an option: sliders for anything sliderable (including GUI Scale, which
	 * vanilla only cycles), switches for booleans, and choice controls for fixed lists.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private SettingRow row(OptionInstance<?> option) {
		Binding.Vanilla binding = new Binding.Vanilla<>(option);
		if (option.values() instanceof OptionInstance.SliderableEnum<?> named) {
			// Named steps (Graphics Preset) read better as a choice than as slider positions.
			return new SettingRow.Choice<>(binding, (List) named.values());
		}
		if (option.values() instanceof OptionInstance.SliderableValueSet<?> slider) {
			// GUI Scale resizes the screen on every change, so it commits on release.
			boolean deferred = option == options.guiScale() || !slider.applyValueImmediately();
			return new SettingRow.Slider<>(binding, SettingRow.SliderModel.vanilla((OptionInstance.SliderableValueSet) slider), deferred);
		}
		List<?> choices = binding.choices();
		if (choices == null || choices.isEmpty()) {
			EphemeraClient.LOGGER.debug("Skipping vanilla option without a known value set: {}", option);
			return null;
		}
		if (option.get() instanceof Boolean && choices.size() == 2 && choices.contains(Boolean.TRUE) && choices.contains(Boolean.FALSE)) {
			return new SettingRow.Toggle(binding);
		}
		return new SettingRow.Choice<>(binding, binding::choices);
	}

	// --- screen-local options ---

	/** Mirrors the "Fullscreen Resolution" slider vanilla builds in {@code VideoSettingsScreen.addOptions}. */
	private static OptionInstance<Integer> createFullscreenResolution(Window window) {
		Monitor monitor = window.findBestMonitor();
		int modes = monitor == null ? 0 : monitor.modeCount();
		int current = monitor == null ? -1 : window.getPreferredFullscreenVideoMode().map(monitor::indexOfMode).orElse(-1);
		return new OptionInstance<>(
			"options.fullscreen.resolution",
			OptionInstance.noTooltip(),
			(caption, index) -> {
				if (monitor == null) {
					return Component.translatable("options.fullscreen.unavailable");
				}
				if (index == -1) {
					return Options.genericValueLabel(caption, Component.translatable("options.fullscreen.current"));
				}
				VideoMode mode = monitor.mode(index);
				return Options.genericValueLabel(caption, Component.translatable("options.fullscreen.entry",
					mode.getWidth(), mode.getHeight(), refreshRate(mode.getRefreshRate()),
					mode.getRedBits() + mode.getGreenBits() + mode.getBlueBits()));
			},
			new OptionInstance.IntRange(-1, modes - 1),
			current,
			index -> {
				if (monitor != null) {
					window.setPreferredFullscreenVideoMode(index == -1 ? Optional.empty() : Optional.of(monitor.mode(index)));
				}
			});
	}

	/** Whole refresh rates print as integers, fractional ones (SDL reports 59.94) with two decimals. */
	private static String refreshRate(double hz) {
		return hz == Math.rint(hz) ? Long.toString((long) hz) : String.format(Locale.ROOT, "%.2f", hz);
	}

	/** An option that exists only on some Minecraft versions (Minecraft ships unobfuscated). */
	private static OptionInstance<?> optionalOption(Options options, String name) {
		try {
			return (OptionInstance<?>) Options.class.getMethod(name).invoke(options);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}
}
