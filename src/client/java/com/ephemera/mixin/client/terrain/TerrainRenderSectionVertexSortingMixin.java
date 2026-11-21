package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.FastVertexSorting;
import com.ephemera.render.terrain.TerrainFeatures;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Swaps the translucent section sorter (used for both the initial compile sort and every
 * resort task) for the stable radix sorter, which produces the identical quad order.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class TerrainRenderSectionVertexSortingMixin {
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
