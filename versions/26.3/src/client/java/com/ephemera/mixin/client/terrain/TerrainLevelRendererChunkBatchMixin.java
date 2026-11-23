package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.BakedOriginAware;
import com.ephemera.render.terrain.ChunkRenderBatcher;
import com.ephemera.render.terrain.SectionLayout;
import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.TerrainTopology;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes 26.3's {@code prepareChunkRendersIndirect} through {@link ChunkRenderBatcher}. When the
 * batcher declines (disabled, missing ducks, unsupported device, failure) vanilla runs, with
 * {@code extractSectionDrawGroups} corrected so region-relative baked meshes receive their baked
 * region origin in both {@code prepareChunkRenders} and {@code prepareChunkRendersIndirect}.
 */
@Mixin(LevelRenderer.class)
public abstract class TerrainLevelRendererChunkBatchMixin implements BakedOriginAware {
	@Shadow
	@Final
	private TextureManager textureManager;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	@Final
	private OptionsRenderState optionsRenderState;

	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Shadow
	private SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"), cancellable = true)
	private void ephemera$batchChunkRendersIndirect(Matrix4fc modelViewMatrix, boolean sortTranslucent,
													CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		ChunkSectionsToRender batched = ChunkRenderBatcher.tryPrepare(
			sectionRenderDispatcher, visibleSections, modelViewMatrix, sortTranslucent,
			textureManager, levelRenderState, optionsRenderState);
		if (batched != null) {
			cir.setReturnValue(batched);
		}
	}

	@Inject(method = "clearVisibleSections", at = @At("HEAD"))
	private void ephemera$visibleSectionsChanged(CallbackInfo ci) {
		TerrainTopology.bumpVisibleSections();
	}

	@ModifyExpressionValue(
		method = "extractSectionDrawGroups",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getRenderOrigin()Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos ephemera$bakedOrigin(BlockPos origin, @Local SectionMesh mesh) {
		if (mesh instanceof SectionMeshRenderCache cache) {
			SectionLayout layout = cache.ephemera$layout();
			if (layout != null && layout.regionRelative) {
				return new BlockPos(layout.originX, layout.originY, layout.originZ);
			}
		}
		return origin;
	}
}
