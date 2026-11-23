package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.render.terrain.RegionalChunkBatchPlan;
import com.ephemera.render.terrain.RegionalRenderHook;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Executes the batcher's {@link RegionalChunkBatchPlan} inside 26.3's unified
 * {@code ChunkSectionsToRender.renderLayers} helper, covering opaque, classic translucent,
 * wireframe, and all three OIT stages inside the caller's open {@link RenderPass}.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class TerrainChunkSectionsToRenderMixin implements RegionalRenderHook {
	@Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true)
	private void ephemera$renderRegionalLayers(ChunkSectionLayer[] layers, GpuSampler sampler, RenderPass pass,
											   GpuTextureView blockAtlas, GpuTextureView lightmap,
											   @Nullable RenderPipeline separateOverride,
											   @Nullable RenderPipeline multiDrawOverride, CallbackInfo ci) {
		RegionalChunkBatchPlan plan = ChunkRenderBatcher.planFor((ChunkSectionsToRender) (Object) this);
		if (plan != null && plan.renderLayers(layers, sampler, pass, blockAtlas, lightmap, multiDrawOverride)) {
			ci.cancel();
		}
	}
}
