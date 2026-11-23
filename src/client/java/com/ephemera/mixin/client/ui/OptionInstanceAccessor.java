package com.ephemera.mixin.client.ui;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

/** Reads the caption, value labels, tooltip and default of a vanilla option for the settings screen. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {
	@Accessor("caption") Component ephemera$caption();
	@Accessor("toString") Function<T, Component> ephemera$toString();
	@Accessor("tooltip") OptionInstance.TooltipSupplier<T> ephemera$tooltip();
	@Accessor("initialValue") T ephemera$initialValue();
}
