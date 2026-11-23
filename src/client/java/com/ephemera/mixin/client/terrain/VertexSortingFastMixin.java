package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.FastVertexSorting;
import com.ephemera.render.terrain.TerrainFeatures;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes {@link VertexSorting#byDistance(Vector3fc)} (and {@link VertexSorting#byDistance(float, float, float)}
 * which delegates to it, including 26.3 {@code StagedVertexBuffer#uploadDrawsToBuffers} translucent
 * feature/particle/entity sorting) through Ephemera's zero-comparator radix sorter {@link FastVertexSorting}.
 */
@Mixin(VertexSorting.class)
public interface VertexSortingFastMixin {
	@Inject(method = "byDistance(Lorg/joml/Vector3fc;)Lcom/mojang/blaze3d/vertex/VertexSorting;", at = @At("HEAD"), cancellable = true)
	private static void ephemera$fastByDistance(Vector3fc origin, CallbackInfoReturnable<VertexSorting> cir) {
		if (TerrainFeatures.fastTranslucentSort()) {
			cir.setReturnValue(new FastVertexSorting(origin.x(), origin.y(), origin.z()));
		}
	}
}
