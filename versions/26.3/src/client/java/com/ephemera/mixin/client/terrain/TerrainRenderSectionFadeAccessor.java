package com.ephemera.mixin.client.terrain;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code uploadedTime} on 26.3 {@code RenderSection} so the chunk batcher can recognise
 * settled sections ({@code now - uploadedTime >= fadeDuration}) without calling {@code getVisibility}.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public interface TerrainRenderSectionFadeAccessor {
	@Accessor("uploadedTime")
	long getEphemeraUploadedTime();
}
