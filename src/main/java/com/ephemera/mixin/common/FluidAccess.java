package com.ephemera.mixin.common;

import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Fluid.class)
public interface FluidAccess {
	@Invoker("isEmpty")
	boolean ephemera$isEmpty();

	@Invoker("isRandomlyTicking")
	boolean ephemera$isRandomlyTicking();
}
