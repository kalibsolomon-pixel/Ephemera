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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps the translucent section sorter for the stable radix sorter, caches
 * {@code uploadedTime + fadeDuration} in the same cache line as the section's fade fields,
 * and bumps {@link TerrainTopology} whenever the section's mesh changes.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class TerrainRenderSectionVertexSortingMixin implements SectionMeshSwapHook {
	@Shadow private long uploadedTime;
	@Shadow private long fadeDuration;

	@Unique private long ephemera$fadeEndMs;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$initFadeEnd(CallbackInfo ci) {
		this.ephemera$fadeEndMs = this.uploadedTime + this.fadeDuration;
	}

	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void ephemera$meshSwapped(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		this.ephemera$fadeEndMs = this.uploadedTime + this.fadeDuration;
		TerrainTopology.bumpTopology();
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void ephemera$meshReset(CallbackInfo ci) {
		this.ephemera$fadeEndMs = this.uploadedTime + this.fadeDuration;
		TerrainTopology.bumpTopology();
	}

	@Inject(method = "setFadeDuration", at = @At("TAIL"))
	private void ephemera$fadeDurationChanged(long duration, CallbackInfo ci) {
		this.ephemera$fadeEndMs = this.uploadedTime + this.fadeDuration;
	}

	@Override
	public long ephemera$fadeEndMs() {
		return this.ephemera$fadeEndMs;
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
