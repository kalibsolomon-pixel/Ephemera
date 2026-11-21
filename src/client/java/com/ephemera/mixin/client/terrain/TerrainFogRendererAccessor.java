package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the debug fog toggle so fog occlusion turns off whenever fog is disabled. */
@Mixin(FogRenderer.class)
public interface TerrainFogRendererAccessor {
	@Accessor("fogEnabled")
	static boolean getEphemeraFogEnabled() {
		throw new AssertionError();
	}
}
