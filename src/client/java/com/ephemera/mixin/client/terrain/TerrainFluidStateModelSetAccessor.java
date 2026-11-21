package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Lets the visible-sprite tracker mark every fluid sprite as always animated. */
@Mixin(FluidStateModelSet.class)
public interface TerrainFluidStateModelSetAccessor {
	@Accessor("modelByFluid")
	Map<Fluid, FluidModel> getEphemeraModelByFluid();
}
