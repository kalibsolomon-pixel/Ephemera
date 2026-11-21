package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.ChunkRenderBatcher;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes {@code prepareChunkRenders} through {@link ChunkRenderBatcher}. When the batcher
 * declines (disabled, missing ducks, failure) vanilla runs unchanged.
 */
@Mixin(LevelRenderer.class)
public abstract class TerrainLevelRendererChunkBatchMixin {
	@Shadow
	@Final
	private TextureManager textureManager;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Shadow
	private SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = "prepareChunkRenders", at = @At("HEAD"), cancellable = true)
	private void ephemera$batchChunkRenders(Matrix4fc modelViewMatrix, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		ChunkSectionsToRender batched = ChunkRenderBatcher.tryPrepare(
			sectionRenderDispatcher, visibleSections, modelViewMatrix, textureManager, levelRenderState);
		if (batched != null) {
			cir.setReturnValue(batched);
		}
	}
}
