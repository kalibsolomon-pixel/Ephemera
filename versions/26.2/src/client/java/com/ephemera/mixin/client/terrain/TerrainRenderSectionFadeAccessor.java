package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the fade timestamps vanilla {@code getVisibility} uses, so the chunk batcher can
 * recognise settled sections ({@code now - uploadedTime >= fadeDuration}, where vanilla
 * returns exactly 1) without calling the injected {@code getVisibility} every frame.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public interface TerrainRenderSectionFadeAccessor {
	@Accessor("uploadedTime")
	long getEphemeraUploadedTime();

	@Accessor("fadeDuration")
	long getEphemeraFadeDuration();
}
