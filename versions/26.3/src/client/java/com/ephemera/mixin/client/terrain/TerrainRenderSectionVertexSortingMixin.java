package com.ephemera.mixin.client.terrain;

import com.ephemera.core.visuals.ChunkFadeMath;
import com.ephemera.render.terrain.FastVertexSorting;
import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.SectionMeshSwapHook;
import com.ephemera.render.terrain.TerrainFeatures;
import com.ephemera.render.terrain.TerrainTopology;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 {@code RenderSection} hooks: swaps translucent sorting for the stable radix sorter,
 * mirrors {@link SectionMeshRenderCache} and block-entity presence, bumps {@link TerrainTopology}
 * on every mesh swap/reset, and computes inline eased fade visibility.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class TerrainRenderSectionVertexSortingMixin implements SectionMeshSwapHook {
	@Shadow @Final public AtomicReference<SectionMesh> sectionMesh;
	@Shadow private long uploadedTime;

	@Unique private volatile @Nullable SectionMeshRenderCache ephemera$compiledCache;
	@Unique private volatile boolean ephemera$hasBlockEntities;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$initMirror(CallbackInfo ci) {
		ephemera$syncCompiledMirror();
	}

	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void ephemera$meshSwapped(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		ephemera$syncCompiledMirror();
		TerrainTopology.bumpTopology();
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void ephemera$meshReset(CallbackInfo ci) {
		this.ephemera$compiledCache = null;
		this.ephemera$hasBlockEntities = false;
		TerrainTopology.bumpTopology();
	}

	@Unique
	private void ephemera$syncCompiledMirror() {
		SectionMesh cur = this.sectionMesh.get();
		if (cur instanceof SectionMeshRenderCache cache) {
			this.ephemera$compiledCache = cache;
			this.ephemera$hasBlockEntities = !cur.getRenderableBlockEntities().isEmpty();
		} else {
			this.ephemera$compiledCache = null;
			this.ephemera$hasBlockEntities = false;
		}
	}

	@Override
	public long ephemera$uploadedTime() {
		return this.uploadedTime;
	}

	@Override
	public float ephemera$visibility(long now, long effectiveFadeMs, boolean smoothChunkFade) {
		if (effectiveFadeMs <= 0L) {
			return 1.0f;
		}
		long elapsed = now - this.uploadedTime;
		if (elapsed >= effectiveFadeMs) {
			return 1.0f;
		}
		float linear = (float) elapsed / (float) effectiveFadeMs;
		if (smoothChunkFade && linear > 0.0f && linear < 1.0f) {
			return ChunkFadeMath.easeOutCubic(linear);
		}
		return linear;
	}

	@Override
	public @Nullable SectionMeshRenderCache ephemera$compiledCache() {
		return this.ephemera$compiledCache;
	}

	@Override
	public boolean ephemera$hasBlockEntities() {
		return this.ephemera$hasBlockEntities;
	}

	@WrapOperation(
		method = "createVertexSorting",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexSorting;byDistance(FFF)Lcom/mojang/blaze3d/vertex/VertexSorting;")
	)
	private VertexSorting ephemera$radixSorting(float x, float y, float z, Operation<VertexSorting> original) {
		if (TerrainFeatures.fastTranslucentSort()) {
			try {
				return FastVertexSorting.forCurrentThread(x, y, z);
			} catch (Throwable t) {
				TerrainFeatures.disableSort(t);
			}
		}
		return original.call(x, y, z);
	}
}
