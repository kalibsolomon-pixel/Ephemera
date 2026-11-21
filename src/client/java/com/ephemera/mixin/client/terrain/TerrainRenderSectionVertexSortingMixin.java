package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.FastVertexSorting;
import com.ephemera.render.terrain.SectionMeshSwapHook;
import com.ephemera.render.terrain.TerrainFeatures;
import com.ephemera.render.terrain.TerrainTopology;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps the translucent section sorter (used for both the initial compile sort and every
 * resort task) for the stable radix sorter, which produces the identical quad order.
 *
 * <p>Also bumps {@link TerrainTopology} whenever the section's mesh is swapped, so the chunk
 * batcher never reuses a frame plan that predates the swap (a swap away from an uncompiled
 * mesh releases nothing and would otherwise go unnoticed).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class TerrainRenderSectionVertexSortingMixin implements SectionMeshSwapHook {
	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void ephemera$meshSwapped(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		TerrainTopology.bumpTopology();
	}

	@WrapOperation(
		method = "createVertexSorting",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexSorting;byDistance(FFF)Lcom/mojang/blaze3d/vertex/VertexSorting;")
	)
	private VertexSorting ephemera$radixSorting(float x, float y, float z, Operation<VertexSorting> original) {
		if (TerrainFeatures.fastTranslucentSort()) {
			try {
				return new FastVertexSorting(x, y, z);
			} catch (Throwable t) {
				TerrainFeatures.disableSort(t);
			}
		}
		return original.call(x, y, z);
	}
}
