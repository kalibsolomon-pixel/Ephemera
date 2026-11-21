package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.render.terrain.RegionalChunkBatchPlan;
import com.ephemera.render.terrain.RegionalRenderHook;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Executes the batcher's {@link RegionalChunkBatchPlan} when this frame's
 * {@code ChunkSectionsToRender} is the one the plan was built for. Any other instance
 * (vanilla, or the batcher's per-section fallback) renders through the unchanged vanilla body.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class TerrainChunkSectionsToRenderMixin implements RegionalRenderHook {
	@Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
	private void ephemera$renderRegional(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
		RegionalChunkBatchPlan plan = ChunkRenderBatcher.planFor((ChunkSectionsToRender) (Object) this);
		if (plan != null && plan.render(group, sampler)) {
			ci.cancel();
		}
	}
}
